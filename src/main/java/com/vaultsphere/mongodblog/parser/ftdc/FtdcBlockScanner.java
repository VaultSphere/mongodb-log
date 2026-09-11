package com.vaultsphere.mongodblog.parser.ftdc;

import org.bson.RawBsonDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;

public final class FtdcBlockScanner {
    public static final int MAX_UNCOMPRESSED_BLOCK_BYTES = 256 * 1024 * 1024;

    private final FtdcBaselineFlattener flattener = new FtdcBaselineFlattener();
    private final FtdcVarIntReader varInts = new FtdcVarIntReader();

    public ScannedBlock scan(byte[] data, long fileOffset, int blockNumber) {
        String location = "FTDC 偏移 " + fileOffset + "，block " + blockNumber + "：";
        try {
            byte[] payload = decompress(data, location);
            if (payload.length < 13) {
                throw new FtdcFormatException(location + "解压内容过短");
            }
            int baselineLength = littleEndianInt(payload, 0);
            if (baselineLength < 5 || baselineLength > payload.length - 8) {
                throw new FtdcFormatException(location + "baseline BSON 长度非法：" + baselineLength);
            }
            RawBsonDocument baselineDocument = new RawBsonDocument(payload, 0, baselineLength);
            FtdcBaselineFlattener.FlattenedBaseline flattened = flattener.flatten(baselineDocument);
            int numAttributes = littleEndianInt(payload, baselineLength);
            int numDeltas = littleEndianInt(payload, baselineLength + 4);
            if (numAttributes < 1 || numAttributes != flattened.values().length) {
                throw new FtdcFormatException(location + "属性数量不一致，声明 " + numAttributes
                        + "，baseline 得到 " + flattened.values().length);
            }
            if (numDeltas < 0 || numDeltas > 10_000_000) {
                throw new FtdcFormatException(location + "delta 数量非法：" + numDeltas);
            }

            int[] offsets = new int[numAttributes];
            int[] zerosAtStart = new int[numAttributes];
            long[] lastValues = Arrays.copyOf(flattened.values(), numAttributes);
            FtdcVarIntReader.Cursor cursor = new FtdcVarIntReader.Cursor(baselineLength + 8);
            long zerosLeft = 0;
            for (int attribute = 0; attribute < numAttributes; attribute++) {
                offsets[attribute] = cursor.position();
                if (zerosLeft > Integer.MAX_VALUE) {
                    throw new FtdcFormatException(location + "零游程过长");
                }
                zerosAtStart[attribute] = (int) zerosLeft;
                for (int point = 0; point < numDeltas; point++) {
                    long delta;
                    if (zerosLeft > 0) {
                        delta = 0;
                        zerosLeft--;
                    } else {
                        delta = varInts.read(payload, cursor);
                        if (delta == 0) {
                            zerosLeft = varInts.read(payload, cursor);
                        }
                    }
                    lastValues[attribute] += delta;
                }
            }
            if (zerosLeft != 0) {
                throw new FtdcFormatException(location + "零游程超出声明的属性数据");
            }
            if (cursor.position() != payload.length) {
                throw new FtdcFormatException(location + "delta 流包含多余数据");
            }
            int startIndex = flattened.schema().indexOf("start");
            if (startIndex < 0) {
                throw new FtdcFormatException(location + "缺少 start 时间指标");
            }
            return new ScannedBlock(flattened.schema(), flattened.values(), lastValues, offsets, zerosAtStart,
                    numDeltas, littleEndianInt(data, 0), data.length - 4,
                    flattened.values()[startIndex], lastValues[startIndex]);
        } catch (FtdcFormatException e) {
            if (e.getMessage().startsWith("FTDC 偏移")) {
                throw e;
            }
            throw new FtdcFormatException(location + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new FtdcFormatException(location + "block 解析失败：" + safeMessage(e), e);
        }
    }

    public byte[] decompress(byte[] data, String location) {
        if (data == null || data.length < 6) {
            throw new FtdcFormatException(location + "压缩 data 过短");
        }
        int declared = littleEndianInt(data, 0);
        if (declared < 5 || declared > MAX_UNCOMPRESSED_BLOCK_BYTES) {
            throw new FtdcFormatException(location + "声明解压长度非法：" + declared);
        }
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(data, 4, data.length - 4));
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(declared, 1024 * 1024))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = inflater.read(buffer)) >= 0) {
                total += read;
                if (total > declared) {
                    throw new FtdcFormatException(location + "实际解压长度超过声明值");
                }
                output.write(buffer, 0, read);
            }
            if (total != declared) {
                throw new FtdcFormatException(location + "实际解压长度 " + total + " 与声明值 " + declared + " 不符");
            }
            return output.toByteArray();
        } catch (FtdcFormatException e) {
            throw e;
        } catch (IOException e) {
            throw new FtdcFormatException(location + "zlib 解压失败", e);
        }
    }

    private int littleEndianInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            throw new FtdcFormatException("读取 int32 越界");
        }
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public record ScannedBlock(
            FtdcSchema schema,
            long[] baseline,
            long[] lastValues,
            int[] deltaOffsets,
            int[] zerosAtStart,
            int numDeltas,
            int declaredLength,
            int compressedLength,
            long startEpochMillis,
            long endEpochMillis
    ) {
    }
}
