module.exports = [
  {
    files: ["**/*.js", "**/*.jsx"],
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      parserOptions: { ecmaFeatures: { jsx: true } }
    },
    rules: {}
  }
];
