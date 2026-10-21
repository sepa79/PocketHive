export type SwarmVariablesMeta = {
  exists: boolean
  hasGlobalVars: boolean
  hasSutVars: boolean
  profiles: { id: string; name: string | null }[]
}
