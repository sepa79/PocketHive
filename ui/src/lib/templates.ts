export interface SwarmTemplate {
  id: string
  name: string
  image: string
}

export const templates: SwarmTemplate[] = [
  { id: 'rest', name: 'REST', image: 'generator-service:latest' },
]
