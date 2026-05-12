const { execute, isAvailable } = require('./executor.cjs');

const ALLOWED = [
  'install', 'ci', 'run', 'test', 'build', 'start', 'stop',
  'list', 'outdated', 'audit', 'update', 'version',
  'pack', 'publish', 'unpublish', 'init'
];
const DANGEROUS = ['--unsafe-perm', '--allow-root'];

class NpmTool {
  async execute(command, args = [], workingDir) {
    if (!ALLOWED.includes(command)) throw new Error(`NPM command '${command}' not allowed`);
    const safe = args.filter(a => !DANGEROUS.some(f => a.startsWith(f)));
    return execute(`npm ${command} ${safe.join(' ')}`.trim(), workingDir);
  }

  async runScript(scriptName, workingDir) { return this.execute('run', [scriptName], workingDir); }
  async installDependencies(workingDir) { return this.execute('ci', [], workingDir); }
  async isAvailable() { return isAvailable('npm'); }
}

module.exports = { NpmTool };
