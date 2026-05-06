const { execute, isAvailable } = require('./executor.cjs');
const { existsSync } = require('fs');
const { resolve } = require('path');

const ALLOWED = [
  'clean', 'compile', 'test', 'package', 'install',
  'verify', 'dependency:tree', 'help:effective-pom',
  'versions:display-dependency-updates'
];

class MavenTool {
  async execute(command, workingDir) {
    if (!ALLOWED.includes(command)) throw new Error(`Maven command '${command}' not allowed`);
    const effectiveDir = workingDir || process.cwd();
    const mvnCmd = existsSync(resolve(effectiveDir, 'mvnw')) ? './mvnw' : 'mvn';
    return execute(`${mvnCmd} ${command}`, workingDir);
  }

  async isAvailable() { return isAvailable('mvn') || isAvailable('./mvnw'); }
}

module.exports = { MavenTool };
