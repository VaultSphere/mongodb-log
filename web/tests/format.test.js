import { describe, expect, it } from 'vitest'
import { formatBytes, formatDuration, formatPercent } from '../src/utils/format.js'

describe('format helpers', () => {
  it('formats durations with explicit units', () => {
    expect(formatDuration(99)).toBe('99 ms')
    expect(formatDuration(1500)).toBe('1.50 s')
    expect(formatDuration(60_000)).toBe('1.00 min')
  })

  it('formats bytes and percentages', () => {
    expect(formatBytes(1024)).toBe('1.00 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.00 MB')
    expect(formatPercent(12.345)).toBe('12.35%')
    expect(formatPercent(null)).toBe('0.00%')
  })
})
