interface Bee {
  role: string
  image: string
  work: { in?: string; out?: string }
}

export const defaultBees: Bee[] = [
  { role: 'generator', image: 'generator-service:latest', work: { out: 'gen' } },
  { role: 'moderator', image: 'moderator-service:latest', work: { in: 'gen', out: 'mod' } },
  { role: 'processor', image: 'processor-service:latest', work: { in: 'mod', out: 'final' } },
  { role: 'postprocessor', image: 'postprocessor-service:latest', work: { in: 'final' } },
]
