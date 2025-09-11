interface Bee {
  role: string
  image: string
  work: { in?: string; out?: string }
}

export const defaultBees: Bee[] = [
  { role: 'generator', image: 'pockethive-generator:latest', work: { out: 'gen' } },
  { role: 'moderator', image: 'pockethive-moderator:latest', work: { in: 'gen', out: 'mod' } },
  { role: 'processor', image: 'pockethive-processor:latest', work: { in: 'mod', out: 'final' } },
  { role: 'postprocessor', image: 'pockethive-postprocessor:latest', work: { in: 'final' } },
]
