// Generated from scenario-manager-service/capabilities/*. Do not edit.

export const scenarioConfigDefaultsByImage = Object.freeze({
  "io-csv-dataset": {
    "inputs.csv.ratePerSec": 1,
    "inputs.csv.rotate": false,
    "inputs.csv.skipHeader": true,
    "inputs.csv.delimiter": ",",
    "inputs.csv.charset": "UTF-8",
    "inputs.csv.startupDelaySeconds": 0,
    "inputs.csv.tickIntervalMs": 1000
  },
  "io-redis-dataset": {
    "inputs.redis.port": 6379,
    "inputs.redis.ssl": false,
    "inputs.redis.listName": "",
    "inputs.redis.sources": [],
    "inputs.redis.pickStrategy": "ROUND_ROBIN",
    "inputs.redis.ratePerSec": 1
  },
  "io-redis-output": {
    "outputs.redis.port": 6379,
    "outputs.redis.ssl": false,
    "outputs.redis.sourceStep": "LAST",
    "outputs.redis.pushDirection": "RPUSH",
    "outputs.redis.routes": [],
    "outputs.redis.targetListTemplate": "",
    "outputs.redis.defaultList": "",
    "outputs.redis.maxLen": -1
  },
  "io-scheduler": {
    "inputs.scheduler.maxMessages": 0
  },
  "postprocessor": {
    "forwardToOutput": false,
    "txOutcomeSinkMode": "NONE",
    "dropTxOutcomeWithoutCallId": true
  },
  "processor": {
    "inputs.type": "RABBITMQ",
    "outputs.type": "RABBITMQ"
  },
  "request-builder": {
    "passThroughOnMissingTemplate": false
  }
})
export const scenarioIoCapabilityBySelector = Object.freeze({
  "INPUT|CSV_DATASET": "io-csv-dataset",
  "INPUT|REDIS_DATASET": "io-redis-dataset",
  "OUTPUT|REDIS": "io-redis-output",
  "INPUT|SCHEDULER": "io-scheduler"
})

export function applyScenarioConfigDefaults(image, config = {}) {
  const result = clone(config)
  applyDefaults(result, scenarioConfigDefaultsByImage[canonicalImageName(image)])
  applyIoDefaults(result, 'INPUT', 'inputs')
  applyIoDefaults(result, 'OUTPUT', 'outputs')
  return result
}

function applyIoDefaults(config, scope, root) {
  const type = readPath(config, root + '.type')
  if (typeof type !== 'string' || type.length === 0) return
  const capabilityImage = scenarioIoCapabilityBySelector[scope + '|' + type]
  if (capabilityImage) applyDefaults(config, scenarioConfigDefaultsByImage[capabilityImage])
}

function applyDefaults(config, defaults) {
  if (!defaults) return
  for (const [path, value] of Object.entries(defaults)) setPathIfAbsent(config, path, clone(value))
}

function setPathIfAbsent(target, path, value) {
  const segments = path.split('.')
  let cursor = target
  for (const segment of segments.slice(0, -1)) {
    if (!isRecord(cursor[segment])) cursor[segment] = {}
    cursor = cursor[segment]
  }
  const leaf = segments.at(-1)
  if (!Object.hasOwn(cursor, leaf)) cursor[leaf] = value
}

function readPath(target, path) {
  let cursor = target
  for (const segment of path.split('.')) {
    if (!isRecord(cursor) || !Object.hasOwn(cursor, segment)) return undefined
    cursor = cursor[segment]
  }
  return cursor
}

function canonicalImageName(image) {
  if (typeof image !== 'string' || image.trim().length === 0) throw new Error('image must be a non-empty string')
  return image.trim().split('@', 1)[0].split('/').at(-1).split(':', 1)[0]
}

function clone(value) {
  if (Array.isArray(value)) return value.map(clone)
  if (!isRecord(value)) return value
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, clone(child)]))
}

function isRecord(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
