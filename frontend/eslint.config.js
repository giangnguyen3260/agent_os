const google = require('gts/build/eslint.config.js');
module.exports = [
  ...google,
  {ignores: ['dist/**', 'coverage/**', '.angular/**']},
  {
    files: ['src/**/*.ts'],
    languageOptions: {parserOptions: {tsconfigRootDir: __dirname, project: './tsconfig.app.json'}},
  },
];
