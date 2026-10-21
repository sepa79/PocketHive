import { parse } from 'yaml'
import generatorManifest from '../../../scenario-manager-service/capabilities/generator.latest.yaml?raw'
import processorManifest from '../../../scenario-manager-service/capabilities/processor.latest.yaml?raw'
import schedulerManifest from '../../../scenario-manager-service/capabilities/io.scheduler.latest.yaml?raw'
import redisManifest from '../../../scenario-manager-service/capabilities/io.redis-dataset.latest.yaml?raw'
import { observedStateFixture } from './swarmState'

type Settings = {
  inputType?: 'SCHEDULER' | 'REDIS_DATASET'
  generatorRate?: number
  processorThreads?: number
  processorMode?: 'THREAD_COUNT' | 'RATE_PER_SEC'
  processorRate?: number
}

export function swarmTuningFixture({
  inputType = 'SCHEDULER', generatorRate = 1, processorThreads = 2,
  processorMode = 'THREAD_COUNT', processorRate = 2.5,
}: Settings = {}) {
  const source = observedStateFixture()
  const generatorConfig = {
    message: { bodyType: 'SIMPLE', body: 'Synthetic tuning payload' },
    inputs: inputType === 'SCHEDULER'
      ? { type: inputType, scheduler: { ratePerSec: generatorRate, maxMessages: 0 } }
      : { type: inputType, redis: { ratePerSec: generatorRate, listName: 'synthetic-ui-only:ready' } },
    outputs: { type: 'RABBITMQ' },
  }
  const processorConfig = {
    inputs: { type: 'RABBITMQ' }, outputs: { type: 'NONE' },
    baseUrl: 'http://synthetic.invalid', mode: processorMode,
    threadCount: processorThreads, ratePerSec: processorRate,
    keepAlive: true, connectionReuse: 'GLOBAL', timeoutMs: 30000, sslVerify: false,
  }
  const generator = { ...source.observation.workers[0], config: generatorConfig }
  const processor = {
    ...generator, role: 'processor', instance: `${source.id}-processor-1`,
    runtime: { ...generator.runtime, image: 'ghcr.io/pockethive/processor:synthetic' },
    config: processorConfig,
  }
  const workers = [generator, processor]
  const state = {
    ...source,
    bees: workers.map(worker => ({ role: worker.role, instance: worker.instance, image: worker.runtime.image })),
    observation: {
      ...source.observation, workers,
      expectedWorkers: workers.map(worker => ({ role: worker.role, instance: worker.instance })),
    },
  }
  const scenario = {
    protocolVersion: '2.0.0', id: source.templateId,
    name: 'Synthetic runtime tuning', description: 'Authored UI-only control fixture.',
    template: {
      image: source.controllerImage,
      bees: workers.map(worker => ({ role: worker.role, image: worker.runtime.image, ports: [], work: {} })),
    },
    topology: { version: 1, edges: [] },
  }
  return {
    state, scenario,
    capabilities: [generatorManifest, processorManifest, schedulerManifest, redisManifest].map(value => parse(value)),
    generator, processor,
  }
}
