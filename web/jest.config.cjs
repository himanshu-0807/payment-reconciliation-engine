/** Jest + React Testing Library (jsdom), TypeScript via ts-jest. */
module.exports = {
  testEnvironment: 'jsdom',
  watchman: false,
  roots: ['<rootDir>/src'],
  setupFilesAfterEnv: ['<rootDir>/src/test/setup.ts'],
  transform: {
    '^.+\\.tsx?$': ['ts-jest', { tsconfig: { jsx: 'react-jsx', esModuleInterop: true, module: 'commonjs',
      moduleResolution: 'node', types: ['jest', '@testing-library/jest-dom'] }, diagnostics: { warnOnly: false } }],
  },
  moduleNameMapper: { '\\.(css)$': 'identity-obj-proxy' },
  collectCoverageFrom: ['src/**/*.{ts,tsx}', '!src/main.tsx', '!src/test/**'],
};
