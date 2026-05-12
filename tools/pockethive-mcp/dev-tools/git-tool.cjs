const { execute, isAvailable } = require('./executor.cjs');

const ALLOWED = [
  'status', 'log', 'diff', 'branch', 'checkout', 'add', 'commit',
  'push', 'pull', 'fetch', 'merge', 'rebase', 'stash', 'tag',
  'remote', 'clone', 'init', 'config', 'show', 'blame'
];
const DANGEROUS_ARGS = ['--force', '-f', '--hard'];

class GitTool {
  async execute(command, args = [], workingDir) {
    if (!ALLOWED.includes(command)) throw new Error(`Git command '${command}' not allowed`);
    if (['push', 'reset'].includes(command) && args.some(a => DANGEROUS_ARGS.some(d => a.includes(d)))) {
      throw new Error('Dangerous git operation not allowed');
    }
    const argStr = args.map(a => `'${a}'`).join(' ');
    return execute(`git ${command} ${argStr}`.trim(), workingDir);
  }

  async getStatus(workingDir) { return this.execute('status', ['--porcelain'], workingDir); }
  async getBranches(workingDir) { return this.execute('branch', ['-a'], workingDir); }
  async getCommitHistory(limit = 10, workingDir) { return this.execute('log', ['--oneline', `-${limit}`], workingDir); }
  async isAvailable() { return isAvailable('git'); }
}

module.exports = { GitTool };
