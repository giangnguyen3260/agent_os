import {defineConfig, devices} from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', {open: 'never'}]],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:8080',
    // Authenticated storage, screenshots and traces can contain personal data.
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  projects: [
    {
      name: 'desktop',
      use: {
        ...devices['Desktop Chrome'],
        viewport: {width: 1440, height: 1000},
      },
    },
    {
      name: 'tablet',
      use: {...devices['Desktop Chrome'], viewport: {width: 768, height: 1024}},
    },
    {
      name: 'mobile',
      use: {...devices['Desktop Chrome'], viewport: {width: 390, height: 844}},
    },
  ],
});
