const { execute, isAvailable } = require('./executor.cjs');

const ALLOWED_CMD = ['build', 'run', 'ps', 'images', 'logs', 'stop', 'rm', 'rmi', 'pull', 'push', 'inspect', 'exec', 'compose', 'cp'];
const ALLOWED_COMPOSE = ['up', 'down', 'build', 'logs', 'ps', 'stop', 'restart'];
const DANGEROUS = ['--privileged', '--cap-add', '--device'];

class DockerTool {
  async execute(command, args = [], workingDir) {
    if (!ALLOWED_CMD.includes(command)) throw new Error(`Docker command '${command}' not allowed`);
    const safe = args.filter(a => !DANGEROUS.some(f => a.startsWith(f)));
    return execute(`docker ${command} ${safe.join(' ')}`.trim(), workingDir);
  }

  async composeExecute(command, args = [], workingDir) {
    if (!ALLOWED_COMPOSE.includes(command)) throw new Error(`Docker Compose command '${command}' not allowed`);
    return this.execute('compose', [command, ...args], workingDir);
  }

  async isAvailable() { return isAvailable('docker'); }
}

module.exports = { DockerTool };
