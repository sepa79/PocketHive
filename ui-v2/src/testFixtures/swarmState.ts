import initial from '../../../common/swarm-model/src/test/resources/io/pockethive/swarm/model/lifecycle/swarm-state-initial.json'
import observed from '../../../common/swarm-model/src/test/resources/io/pockethive/swarm/model/lifecycle/swarm-state-observed.json'

export function initialStateFixture() {
  return structuredClone(initial)
}

export function observedStateFixture() {
  return structuredClone(observed)
}
