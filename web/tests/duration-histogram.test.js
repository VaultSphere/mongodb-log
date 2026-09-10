import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DurationHistogram from '../src/components/DurationHistogram.vue'

const setOption = vi.fn()

vi.mock('echarts', () => ({
  init: () => ({ setOption, resize: vi.fn(), dispose: vi.fn() }),
}))

const keys = [
  'lt_100ms',
  '100ms_500ms',
  '500ms_1s',
  '1s_3s',
  '3s_10s',
  '10s_30s',
  '30s_60s',
  'gte_60s',
]

describe('DurationHistogram', () => {
  beforeEach(() => setOption.mockClear())

  it('renders the eight buckets in API order and initializes the chart', async () => {
    const buckets = keys.map((key, index) => ({
      key,
      label: key,
      count: index + 1,
      percentage: 1,
      totalDurationMillis: 100,
      averageDurationMillis: 100,
      maxDurationMillis: 100,
    }))

    const wrapper = mount(DurationHistogram, { props: { buckets } })
    await nextTick()

    expect(wrapper.findAll('[data-bucket-key]').map((row) => row.attributes('data-bucket-key'))).toEqual(keys)
    expect(setOption).toHaveBeenCalledOnce()
  })

  it('explains when no slow queries are available', () => {
    const wrapper = mount(DurationHistogram, { props: { buckets: [] } })

    expect(wrapper.text()).toContain('暂无慢查询耗时数据')
    expect(setOption).not.toHaveBeenCalled()
  })
})
