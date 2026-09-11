package com.vaultsphere.mongodblog.analysis.ftdc;

import com.vaultsphere.mongodblog.parser.ftdc.FtdcBlockScanner;
import com.vaultsphere.mongodblog.parser.ftdc.FtdcFormatException;
import com.vaultsphere.mongodblog.parser.ftdc.FtdcVarIntReader;
import com.vaultsphere.mongodblog.storage.ftdc.FtdcIndexReader;
import com.vaultsphere.mongodblog.storage.ftdc.FtdcIndexWriter;
import org.bson.RawBsonDocument;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class FtdcMetricDecoder {
    private final FtdcBlockScanner scanner = new FtdcBlockScanner();
    private final FtdcVarIntReader varInts = new FtdcVarIntReader();

    public DecodedBlock decode(Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files,
                               FtdcIndexReader.MetricBlockIndex block) throws IOException {
        byte[] payload = readPayload(sourceDirectory, files, block.fileId(), block.blockOrdinal(), block.fileOffset(),
                block.documentLength(), block.compressedLength(), block.declaredLength());
        long[] times = decodeColumn(payload, block.numDeltas(), block.timeBaseline(),
                block.timeDeltaOffset(), block.timeZerosAtStart());
        long[] values = block.metricDeltaOffset() == block.timeDeltaOffset()
                ? times
                : decodeColumn(payload, block.numDeltas(), block.metricBaseline(),
                block.metricDeltaOffset(), block.metricZerosAtStart());
        return new DecodedBlock(times, values);
    }

    public GroupDecodedBlock decodeGroup(Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files,
                                         FtdcIndexReader.GroupMetricBlockIndex block) throws IOException {
        byte[] payload = readPayload(sourceDirectory, files, block.fileId(), block.blockOrdinal(), block.fileOffset(),
                block.documentLength(), block.compressedLength(), block.declaredLength());
        long[] times = decodeColumn(payload, block.numDeltas(), block.timeBaseline(),
                block.timeDeltaOffset(), block.timeZerosAtStart());
        long[][] values = new long[block.metricIndexes().length][];
        for (int i = 0; i < values.length; i++) {
            values[i] = block.metricDeltaOffsets()[i] == block.timeDeltaOffset()
                    ? times
                    : decodeColumn(payload, block.numDeltas(), block.metricBaselines()[i],
                    block.metricDeltaOffsets()[i], block.metricZerosAtStart()[i]);
        }
        return new GroupDecodedBlock(times, block.metricIndexes(), values);
    }

    private byte[] readPayload(Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files, int fileId,
                               int blockOrdinal, long fileOffset, int documentLength,
                               int compressedLength, int declaredLength) throws IOException {
        if (fileId < 0 || fileId >= files.size()) {
            throw new FtdcFormatException("FTDC 索引引用了不存在的源文件");
        }
        Path source = sourceDirectory.resolve(files.get(fileId).storedName()).normalize();
        if (!source.startsWith(sourceDirectory.toAbsolutePath().normalize())) {
            throw new FtdcFormatException("FTDC 源文件路径越界");
        }
        byte[] document = new byte[documentLength];
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            ByteBuffer target = ByteBuffer.wrap(document);
            while (target.hasRemaining()) {
                int read = channel.read(target, fileOffset + target.position());
                if (read < 0) throw new EOFException("FTDC 源 block 截断");
            }
        }
        byte[] data;
        try {
            data = new RawBsonDocument(document).getBinary("data").getData();
        } catch (RuntimeException e) {
            throw new FtdcFormatException("FTDC 源 block BSON 无法解析", e);
        }
        if (data.length - 4 != compressedLength) {
            throw new FtdcFormatException("FTDC 源 block 压缩长度与索引不一致");
        }
        byte[] payload = scanner.decompress(data,
                "FTDC 偏移 " + fileOffset + "，block " + blockOrdinal + "：");
        if (payload.length != declaredLength) {
            throw new FtdcFormatException("FTDC 源 block 解压长度与索引不一致");
        }
        return payload;
    }

    private long[] decodeColumn(byte[] payload, int numDeltas, long baseline, int offset, int initialZeros) {
        if (offset < 0 || offset > payload.length || initialZeros < 0) {
            throw new FtdcFormatException("FTDC 指标索引位置非法");
        }
        long[] values = new long[numDeltas + 1];
        values[0] = baseline;
        long current = baseline;
        long zerosLeft = initialZeros;
        FtdcVarIntReader.Cursor cursor = new FtdcVarIntReader.Cursor(offset);
        for (int point = 1; point <= numDeltas; point++) {
            long delta;
            if (zerosLeft > 0) {
                delta = 0;
                zerosLeft--;
            } else {
                delta = varInts.read(payload, cursor);
                if (delta == 0) zerosLeft = varInts.read(payload, cursor);
            }
            current += delta;
            values[point] = current;
        }
        return values;
    }

    public record DecodedBlock(long[] timestamps, long[] values) {
    }

    public record GroupDecodedBlock(long[] timestamps, int[] metricIndexes, long[][] values) {
    }
}
