import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import App from '../src/App.vue'

describe('App', () => {
  it('shows the offline retention policy', () => {
    const wrapper = mount(App)

    expect(wrapper.text()).toContain('MongoDB Log Analyzer')
    expect(wrapper.text()).toContain('Top 5000')
  })
})
