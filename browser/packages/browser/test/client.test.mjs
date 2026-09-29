import assert from 'node:assert/strict'
import test from 'node:test'

import { createWebConfig } from '../src/index.ts'

test('get returns an immutable fallback state before the lazy connection starts', () => {
  let fetchCalls = 0
  const fallback = { theme: 'light' }
  const config = createWebConfig({
    endpoint: '/_web-config/v1/stream',
    definitions: {
      ui: {
        decode: value => value,
        fallback,
      },
    },
    fetch: async () => {
      fetchCalls += 1
      throw new Error('get must not start the connection')
    },
  })

  const state = config.get('ui')

  assert.deepEqual(state, {
    value: { theme: 'light' },
    source: 'fallback',
    status: 'loading',
    stale: true,
  })
  assert.equal(fetchCalls, 0)
  assert.ok(Object.isFrozen(state))
  assert.ok(Object.isFrozen(state.value))
  fallback.theme = 'mutated outside the client'
  assert.equal(config.get('ui').value.theme, 'light')
})

test('subscribe notifies synchronously and all listeners share one lazy request', async () => {
  const requests = []
  const config = createWebConfig({
    endpoint: '/_web-config/v1/stream',
    definitions: {
      ui: { decode: value => value, fallback: { theme: 'light' } },
      flags: { decode: value => value, fallback: {} },
    },
    credentials: 'include',
    fetch: async (input, init) => {
      requests.push({ input: String(input), init })
      return new Promise(() => {})
    },
  })
  const seen = []

  const offUi = config.subscribe('ui', state => seen.push(['ui', state.status]))
  assert.deepEqual(seen, [['ui', 'loading']])
  config.subscribe('flags', state => seen.push(['flags', state.status]))
  await Promise.resolve()

  assert.equal(requests.length, 1)
  assert.equal(requests[0].input, '/_web-config/v1/stream?key=flags&key=ui')
  assert.equal(requests[0].init.credentials, 'include')
  assert.equal(requests[0].init.headers.Accept, 'text/event-stream')

  offUi()
  offUi()
})

test('a split UTF-8 multiline SSE snapshot becomes the decoded ready state', async () => {
  const bytes = new TextEncoder().encode(
    ': ping\r\n\r\nevent: snapshot\r\n'
      + 'data: {"protocol":1,"streamId":"stream-a","seq":0,"entries":\r\n'
      + 'data: {"ui":{"status":"ready","hasValue":true,"value":{"text":"你好"},"contentHash":"sha256:abc"}}}\r\n\r\n',
  )
  const splitInsideChineseCharacter = bytes.indexOf(0xe4) + 1
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(bytes.slice(0, splitInsideChineseCharacter))
      controller.enqueue(bytes.slice(splitInsideChineseCharacter))
    },
  })
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: {
      ui: {
        decode(value) {
          if (typeof value?.text !== 'string') throw new Error('text required')
          return { text: value.text }
        },
        fallback: { text: 'fallback' },
      },
    },
    fetch: async () => new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }),
  })
  const seen = []

  config.subscribe('ui', state => seen.push(state))
  await waitFor(() => seen.some(state => state.status === 'ready'))

  assert.deepEqual(seen.at(-1), {
    value: { text: '你好' },
    source: 'remote',
    status: 'ready',
    stale: false,
  })
  assert.ok(Object.isFrozen(seen.at(-1).value))
  config.close()
})

test('change events preserve LKG across stale states and deletion clears it', async () => {
  const messages = [
    ['snapshot', { protocol: 1, streamId: 's1', seq: 0, entries: {
      ui: readyEntry('one'),
    } }],
    ['change', { protocol: 1, streamId: 's1', seq: 1, key: 'ui', entry: {
      status: 'invalid', hasValue: true, value: { text: 'one' },
      contentHash: 'sha256:one', errorCode: 'INVALID_JSON',
    } }],
    ['change', { protocol: 1, streamId: 's1', seq: 2, key: 'ui', entry: {
      status: 'unavailable', hasValue: true, value: { text: 'one' },
      contentHash: 'sha256:one', errorCode: 'SOURCE_UNAVAILABLE',
    } }],
    ['change', { protocol: 1, streamId: 's1', seq: 3, key: 'ui', entry: {
      status: 'deleted', hasValue: false,
    } }],
    ['change', { protocol: 1, streamId: 's1', seq: 4, key: 'ui', entry: readyEntry(42) }],
    ['change', { protocol: 1, streamId: 's1', seq: 5, key: 'ui', entry: readyEntry('two') }],
  ]
  const encoded = new TextEncoder().encode(messages
    .map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    .join(''))
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: {
      ui: {
        decode(value) {
          if (typeof value?.text !== 'string') throw new Error('text required')
          return { text: value.text }
        },
        fallback: { text: 'fallback' },
      },
    },
    fetch: async () => new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(encoded)
      },
    })),
  })
  const seen = []

  config.subscribe('ui', state => seen.push({
    value: state.value?.text,
    source: state.source,
    status: state.status,
    stale: state.stale,
    errorCode: state.errorCode,
  }))
  await waitFor(() => seen.length === 7)

  assert.deepEqual(seen, [
    { value: 'fallback', source: 'fallback', status: 'loading', stale: true, errorCode: undefined },
    { value: 'one', source: 'remote', status: 'ready', stale: false, errorCode: undefined },
    { value: 'one', source: 'remote', status: 'invalid', stale: true, errorCode: 'INVALID_JSON' },
    { value: 'one', source: 'remote', status: 'unavailable', stale: true, errorCode: 'SOURCE_UNAVAILABLE' },
    { value: 'fallback', source: 'fallback', status: 'deleted', stale: false, errorCode: undefined },
    { value: 'fallback', source: 'fallback', status: 'invalid', stale: true, errorCode: 'DECODER_ERROR' },
    { value: 'two', source: 'remote', status: 'ready', stale: false, errorCode: undefined },
  ])
  config.close()
})

test('a decoder failure preserves fallback and reports DECODER_ERROR', async () => {
  const errors = []
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: {
      ui: {
        decode(value) {
          if (typeof value?.text !== 'string') throw new Error('text required')
          return { text: value.text }
        },
        fallback: { text: 'fallback' },
      },
    },
    onError: error => errors.push(error),
    fetch: async () => sseResponse([['snapshot', {
      protocol: 1,
      streamId: 'decoder-error',
      seq: 0,
      entries: { ui: readyEntry(42) },
    }]], false),
  })

  config.subscribe('ui', () => {})
  await waitFor(() => config.get('ui').status === 'invalid')

  assert.deepEqual(config.get('ui'), {
    value: { text: 'fallback' },
    source: 'fallback',
    status: 'invalid',
    stale: true,
    errorCode: 'DECODER_ERROR',
  })
  assert.equal(errors.length, 1)
  assert.equal(errors[0].code, 'DECODER_ERROR')
  assert.match(errors[0].message, /ui/)
  config.close()
})

test('invalid wire entry field combinations stop the protocol', async () => {
  const invalidEntries = [
    { status: 'ready', hasValue: true, value: { text: 'missing hash' } },
    { status: 'ready', hasValue: true, value: 'not an object', contentHash: 'sha256:x' },
    { status: 'invalid', hasValue: true, value: { text: 'missing hash' } },
    { status: 'unavailable', hasValue: false, contentHash: 'sha256:orphan' },
  ]

  for (const [index, entry] of invalidEntries.entries()) {
    const errors = []
    const config = createWebConfig({
      endpoint: '/stream',
      definitions: { ui: { decode: value => value, fallback: { text: 'fallback' } } },
      onError: error => errors.push(error),
      fetch: async () => sseResponse([['snapshot', {
        protocol: 1,
        streamId: `invalid-entry-${index}`,
        seq: 0,
        entries: { ui: entry },
      }]], false),
    })

    config.subscribe('ui', () => {})
    await waitFor(() => errors.length === 1)

    assert.equal(errors[0].code, 'PROTOCOL_ERROR')
    assert.equal(config.get('ui').status, 'loading')
    config.close()
  }
})

test('close aborts the stream and permanently rejects subscriptions and late events', async () => {
  let streamController
  let requestSignal
  const body = new ReadableStream({
    start(controller) {
      streamController = controller
    },
  })
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: { ui: { decode: value => value, fallback: { text: 'fallback' } } },
    fetch: async (_input, init) => {
      requestSignal = init.signal
      return new Response(body)
    },
  })
  const seen = []
  config.subscribe('ui', state => seen.push(state.status))
  await waitFor(() => requestSignal !== undefined)

  config.close()
  config.close()
  assert.equal(requestSignal.aborted, true)
  assert.throws(
    () => config.subscribe('ui', () => {}),
    error => error.code === 'CLIENT_CLOSED',
  )

  streamController.enqueue(new TextEncoder().encode(
    `event: snapshot\ndata: ${JSON.stringify({
      protocol: 1,
      streamId: 'late',
      seq: 0,
      entries: { ui: readyEntry('late') },
    })}\n\n`,
  ))
  streamController.close()
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.deepEqual(seen, ['loading'])
})

test('a sequence gap reports PROTOCOL_ERROR and stops later events', async () => {
  let requestSignal
  let fetchCalls = 0
  const errors = []
  const payload = [
    ['snapshot', { protocol: 1, streamId: 's1', seq: 0, entries: { ui: readyEntry('one') } }],
    ['change', { protocol: 1, streamId: 's1', seq: 2, key: 'ui', entry: readyEntry('gap') }],
    ['change', { protocol: 1, streamId: 's1', seq: 3, key: 'ui', entry: readyEntry('late') }],
  ].map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join('')
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: { ui: { decode: value => ({ text: value.text }), fallback: { text: 'fallback' } } },
    onError: error => errors.push(error),
    fetch: async (_input, init) => {
      fetchCalls += 1
      requestSignal = init.signal
      return new Response(new TextEncoder().encode(payload))
    },
  })
  const seen = []

  config.subscribe('ui', state => seen.push(state.value.text))
  await waitFor(() => errors.length === 1)

  assert.equal(errors[0].code, 'PROTOCOL_ERROR')
  assert.equal(requestSignal.aborted, true)
  assert.deepEqual(seen, ['fallback', 'one'])
  await new Promise(resolve => setTimeout(resolve, 5))
  assert.equal(fetchCalls, 1)
  assert.equal(config.get('ui').value.text, 'one')
})

test('duplicate and old change sequences are ignored within the same stream', async () => {
  const errors = []
  const payload = [
    ['snapshot', { protocol: 1, streamId: 's1', seq: 0, entries: { ui: readyEntry('one') } }],
    ['change', { protocol: 1, streamId: 's1', seq: 1, key: 'ui', entry: readyEntry('two') }],
    ['change', { protocol: 1, streamId: 's1', seq: 1, key: 'ui', entry: readyEntry('duplicate') }],
    ['change', { protocol: 1, streamId: 's1', seq: 0, key: 'ui', entry: readyEntry('old') }],
    ['change', { protocol: 1, streamId: 's1', seq: 2, key: 'ui', entry: readyEntry('three') }],
  ].map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join('')
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: { ui: { decode: value => value, fallback: { text: 'fallback' } } },
    onError: error => errors.push(error),
    fetch: async () => new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(payload))
      },
    })),
  })
  const seen = []

  config.subscribe('ui', state => seen.push(state.value.text))
  await waitFor(() => seen.includes('three'))

  assert.deepEqual(seen, ['fallback', 'one', 'two', 'three'])
  assert.deepEqual(errors, [])
  config.close()
})

test('EOF marks current values unavailable and reconnects from a new snapshot', async () => {
  const originalRandom = Math.random
  Math.random = () => 0
  let fetchCalls = 0
  const responses = [
    sseResponse([['snapshot', {
      protocol: 1, streamId: 'first', seq: 0, entries: { ui: readyEntry('one') },
    }]]),
    sseResponse([['snapshot', {
      protocol: 1, streamId: 'second', seq: 0, entries: { ui: readyEntry('two') },
    }]], false),
  ]
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: { ui: { decode: value => ({ text: value.text }), fallback: { text: 'fallback' } } },
    fetch: async () => responses[fetchCalls++],
  })
  const seen = []

  try {
    config.subscribe('ui', state => seen.push([state.status, state.value.text]))
    await waitFor(() => seen.some(([status, value]) => status === 'ready' && value === 'two'))
  } finally {
    Math.random = originalRandom
    config.close()
  }

  assert.equal(fetchCalls, 2)
  assert.deepEqual(seen, [
    ['loading', 'fallback'],
    ['ready', 'one'],
    ['unavailable', 'one'],
    ['ready', 'two'],
  ])
})

test('a network read failure after a snapshot reconnects instead of stopping the protocol', async () => {
  const originalRandom = Math.random
  Math.random = () => 0
  let firstController
  let fetchCalls = 0
  const errors = []
  const first = new Response(new ReadableStream({
    start(controller) {
      firstController = controller
      controller.enqueue(new TextEncoder().encode(
        `event: snapshot\ndata: ${JSON.stringify({
          protocol: 1, streamId: 'first', seq: 0, entries: { ui: readyEntry('one') },
        })}\n\n`,
      ))
    },
  }))
  const second = sseResponse([['snapshot', {
    protocol: 1, streamId: 'second', seq: 0, entries: { ui: readyEntry('two') },
  }]], false)
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: { ui: { decode: value => ({ text: value.text }), fallback: { text: 'fallback' } } },
    onError: error => errors.push(error),
    fetch: async () => [first, second][fetchCalls++],
  })
  const seen = []

  try {
    config.subscribe('ui', state => seen.push([state.status, state.value.text]))
    await waitFor(() => config.get('ui').value.text === 'one')
    firstController.error(new TypeError('socket reset'))
    await waitFor(() => config.get('ui').value.text === 'two')
  } finally {
    Math.random = originalRandom
    config.close()
  }

  assert.equal(fetchCalls, 2)
  assert.deepEqual(errors, [])
  assert.deepEqual(seen, [
    ['loading', 'fallback'],
    ['ready', 'one'],
    ['unavailable', 'one'],
    ['ready', 'two'],
  ])
})

test('a non-retryable HTTP error is reported once without reconnecting', async () => {
  let fetchCalls = 0
  const errors = []
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: { ui: { decode: value => value, fallback: {} } },
    onError: error => errors.push(error),
    fetch: async () => {
      fetchCalls += 1
      return new Response(JSON.stringify({
        code: 'UNKNOWN_KEY',
        message: 'One or more requested keys are unavailable',
      }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' },
      })
    },
  })

  config.subscribe('ui', () => {})
  await waitFor(() => errors.length === 1)
  await new Promise(resolve => setTimeout(resolve, 5))

  assert.equal(errors[0].code, 'UNKNOWN_KEY')
  assert.equal(errors[0].message, 'One or more requested keys are unavailable')
  assert.equal(fetchCalls, 1)
  assert.equal(config.get('ui').status, 'loading')
  config.close()
})

test('429 honors Retry-After before reconnecting', async () => {
  const originalSetTimeout = globalThis.setTimeout
  const recordedDelays = []
  let fetchCalls = 0
  globalThis.setTimeout = (callback, delay = 0, ...args) => {
    recordedDelays.push(delay)
    if (delay === 2000) queueMicrotask(() => callback(...args))
    return 1
  }
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: { ui: { decode: value => value, fallback: {} } },
    fetch: async () => {
      fetchCalls += 1
      if (fetchCalls === 1) {
        return new Response('', { status: 429, headers: { 'Retry-After': '2' } })
      }
      return sseResponse([['snapshot', {
        protocol: 1, streamId: 'after-limit', seq: 0, entries: { ui: readyEntry('ready') },
      }]], false)
    },
  })

  try {
    config.subscribe('ui', () => {})
    await waitForMicrotasks(() => config.get('ui').status === 'ready')
  } finally {
    globalThis.setTimeout = originalSetTimeout
    config.close()
  }

  assert.equal(fetchCalls, 2)
  assert.ok(recordedDelays.includes(2000))
})

test('a server 5xx is treated as a retryable source outage', async () => {
  const originalSetTimeout = globalThis.setTimeout
  const originalRandom = Math.random
  const recordedDelays = []
  const errors = []
  let fetchCalls = 0
  globalThis.setTimeout = (callback, delay = 0, ...args) => {
    recordedDelays.push(delay)
    if (delay === 250) queueMicrotask(() => callback(...args))
    return 1
  }
  Math.random = () => 0.25
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: { ui: { decode: value => value, fallback: { text: 'fallback' } } },
    onError: error => errors.push(error),
    fetch: async () => {
      fetchCalls += 1
      if (fetchCalls === 1) {
        return new Response(JSON.stringify({ code: 'MODULE_STOPPED' }), { status: 503 })
      }
      return sseResponse([['snapshot', {
        protocol: 1, streamId: 'recovered', seq: 0, entries: { ui: readyEntry('ready') },
      }]], false)
    },
  })
  const seen = []

  try {
    config.subscribe('ui', state => seen.push(state.status))
    await waitForMicrotasks(() => config.get('ui').status === 'ready')
  } finally {
    globalThis.setTimeout = originalSetTimeout
    Math.random = originalRandom
    config.close()
  }

  assert.equal(fetchCalls, 2)
  assert.deepEqual(errors, [])
  assert.deepEqual(seen, ['loading', 'unavailable', 'ready'])
  assert.ok(recordedDelays.includes(250))
})

test('forty-five seconds without bytes aborts and reconnects the stream', async () => {
  const originalSetTimeout = globalThis.setTimeout
  const originalClearTimeout = globalThis.clearTimeout
  const originalRandom = Math.random
  const timers = []
  let nextTimerId = 1
  let fetchCalls = 0
  let firstController
  let firstSignal
  globalThis.setTimeout = (callback, delay = 0) => {
    const timer = { id: nextTimerId++, callback, delay, cleared: false }
    timers.push(timer)
    return timer.id
  }
  globalThis.clearTimeout = id => {
    const timer = timers.find(candidate => candidate.id === id)
    if (timer) timer.cleared = true
  }
  Math.random = () => 0
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: { ui: { decode: value => value, fallback: {} } },
    fetch: async (_input, init) => {
      fetchCalls += 1
      if (fetchCalls === 1) firstSignal = init.signal
      const payload = new TextEncoder().encode(
        `event: snapshot\ndata: ${JSON.stringify({
          protocol: 1, streamId: `stream-${fetchCalls}`, seq: 0, entries: { ui: readyEntry(fetchCalls) },
        })}\n\n`,
      )
      return new Response(new ReadableStream({
        start(controller) {
          if (fetchCalls === 1) firstController = controller
          controller.enqueue(payload)
        },
      }))
    },
  })

  try {
    config.subscribe('ui', () => {})
    await waitForMicrotasks(() => config.get('ui').value?.text === 1)
    await waitForMicrotasks(() => activeTimers(timers, 45_000).length === 1)
    const firstInactivityTimer = activeTimers(timers, 45_000)[0]
    firstController.enqueue(new TextEncoder().encode(': heartbeat\n\n'))
    await waitForMicrotasks(() => firstInactivityTimer.cleared)
    assert.equal(activeTimers(timers, 45_000).length, 1)

    runTimer(timers, 45_000)
    await waitForMicrotasks(() => firstSignal.aborted && activeTimers(timers, 0).length === 1)
    runTimer(timers, 0)
    await waitForMicrotasks(() => config.get('ui').value?.text === 2)
  } finally {
    globalThis.setTimeout = originalSetTimeout
    globalThis.clearTimeout = originalClearTimeout
    Math.random = originalRandom
    config.close()
  }

  assert.equal(fetchCalls, 2)
})

test('returning to a visible page immediately replaces an overdue stream', async () => {
  const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
  const originalSetTimeout = globalThis.setTimeout
  const originalClearTimeout = globalThis.clearTimeout
  const originalNow = Date.now
  const timers = []
  const visibilityListeners = new Set()
  const page = {
    visibilityState: 'hidden',
    addEventListener(type, listener) {
      if (type === 'visibilitychange') visibilityListeners.add(listener)
    },
    removeEventListener(type, listener) {
      if (type === 'visibilitychange') visibilityListeners.delete(listener)
    },
  }
  let now = 0
  let nextTimerId = 1
  let fetchCalls = 0
  let firstSignal
  Object.defineProperty(globalThis, 'document', { configurable: true, value: page })
  globalThis.setTimeout = (callback, delay = 0) => {
    const timer = { id: nextTimerId++, callback, delay, cleared: false }
    timers.push(timer)
    return timer.id
  }
  globalThis.clearTimeout = id => {
    const timer = timers.find(candidate => candidate.id === id)
    if (timer) timer.cleared = true
  }
  Date.now = () => now
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: { ui: { decode: value => value, fallback: {} } },
    fetch: async (_input, init) => {
      fetchCalls += 1
      if (fetchCalls === 1) firstSignal = init.signal
      return sseResponse([['snapshot', {
        protocol: 1,
        streamId: `visible-${fetchCalls}`,
        seq: 0,
        entries: { ui: readyEntry(fetchCalls) },
      }]], false)
    },
  })

  try {
    config.subscribe('ui', () => {})
    await waitForMicrotasks(() => config.get('ui').value?.text === 1)
    now = 46_000
    page.visibilityState = 'visible'
    for (const listener of visibilityListeners) listener()
    await waitForMicrotasks(() => config.get('ui').value?.text === 2)

    assert.equal(firstSignal.aborted, true)
    assert.equal(fetchCalls, 2)
  } finally {
    config.close()
    globalThis.setTimeout = originalSetTimeout
    globalThis.clearTimeout = originalClearTimeout
    Date.now = originalNow
    if (originalDocument === undefined) delete globalThis.document
    else Object.defineProperty(globalThis, 'document', originalDocument)
  }

  assert.equal(visibilityListeners.size, 0)
})

test('thirty healthy seconds reset the exponential backoff attempt', async () => {
  const originalSetTimeout = globalThis.setTimeout
  const originalClearTimeout = globalThis.clearTimeout
  const originalRandom = Math.random
  const timers = []
  let nextTimerId = 1
  let fetchCalls = 0
  let healthyController
  globalThis.setTimeout = (callback, delay = 0) => {
    const timer = { id: nextTimerId++, callback, delay, cleared: false }
    timers.push(timer)
    return timer.id
  }
  globalThis.clearTimeout = id => {
    const timer = timers.find(candidate => candidate.id === id)
    if (timer) timer.cleared = true
  }
  Math.random = () => 0.5
  const config = createWebConfig({
    endpoint: '/stream',
    definitions: { ui: { decode: value => value, fallback: {} } },
    fetch: async () => {
      fetchCalls += 1
      if (fetchCalls === 1) throw new TypeError('offline')
      const payload = new TextEncoder().encode(
        `event: snapshot\ndata: ${JSON.stringify({
          protocol: 1, streamId: `stream-${fetchCalls}`, seq: 0, entries: { ui: readyEntry('ok') },
        })}\n\n`,
      )
      return new Response(new ReadableStream({
        start(controller) {
          healthyController = controller
          controller.enqueue(payload)
        },
      }))
    },
  })

  try {
    config.subscribe('ui', () => {})
    await waitForMicrotasks(() => timers.some(timer => timer.delay === 500))
    runTimer(timers, 500)
    await waitForMicrotasks(() => config.get('ui').status === 'ready')
    await waitForMicrotasks(() => timers.some(timer => timer.delay === 30_000))
    runTimer(timers, 30_000)
    healthyController.close()
    await waitForMicrotasks(() => timers.filter(timer => timer.delay === 500).length === 2)
  } finally {
    globalThis.setTimeout = originalSetTimeout
    globalThis.clearTimeout = originalClearTimeout
    Math.random = originalRandom
    config.close()
  }

  assert.equal(fetchCalls, 2)
})

function runTimer(timers, delay) {
  const timer = timers.find(candidate => candidate.delay === delay && !candidate.cleared)
  assert.ok(timer, `missing active ${delay}ms timer`)
  timer.cleared = true
  timer.callback()
}

function activeTimers(timers, delay) {
  return timers.filter(timer => timer.delay === delay && !timer.cleared)
}

async function waitForMicrotasks(condition) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (condition()) return
    await Promise.resolve()
  }
  assert.fail('microtask condition was not reached')
}

function sseResponse(events, close = true) {
  const encoded = new TextEncoder().encode(events
    .map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    .join(''))
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(encoded)
      if (close) controller.close()
    },
  }))
}

function readyEntry(text) {
  return {
    status: 'ready',
    hasValue: true,
    value: { text },
    contentHash: `sha256:${String(text)}`,
  }
}

async function waitFor(condition) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (condition()) return
    await new Promise(resolve => setTimeout(resolve, 0))
  }
  assert.fail('condition was not reached')
}

test('omitted decode passes the validated object through and endpoint has a default', async () => {
  const requests = []
  const config = createWebConfig({
    definitions: { ui: {} },
    fetch: async (input, init) => {
      requests.push(String(input))
      return sseResponse([
        ['snapshot', { protocol: 1, streamId: 's1', seq: 0, entries: {
          ui: { status: 'ready', hasValue: true,
            value: { announcement: 'hello', count: 1 }, contentHash: 'sha256:h1' },
        } }],
      ])
    },
  })
  const seen = []
  config.subscribe('ui', state => seen.push(state))
  await waitFor(() => seen.some(state => state.status === 'ready'))

  assert.equal(requests[0], '/_web-config/v1/stream?key=ui')
  const ready = seen.find(state => state.status === 'ready')
  assert.deepEqual(ready, {
    value: { announcement: 'hello', count: 1 },
    source: 'remote',
    status: 'ready',
    stale: false,
  })
  assert.ok(Object.isFrozen(ready.value))
  config.close()
})

test('a rejected decoder warns once on the console and keeps the last known good value', async () => {
  const warnings = []
  const originalWarn = console.warn
  console.warn = message => warnings.push(String(message))
  try {
    const config = createWebConfig({
      endpoint: '/stream',
      definitions: {
        ui: { decode(value) {
          if (typeof value?.announcement !== 'string') throw new Error('announcement must be a string')
          return { announcement: value.announcement }
        } },
      },
      fetch: async () => sseResponse([
        ['snapshot', { protocol: 1, streamId: 's1', seq: 0, entries: {
          ui: { status: 'ready', hasValue: true,
            value: { announcement: 'ok' }, contentHash: 'sha256:ok' },
        } }],
        ['change', { protocol: 1, streamId: 's1', seq: 1, key: 'ui', entry: {
          status: 'ready', hasValue: true, value: { announcement: 123 }, contentHash: 'sha256:bad1',
        } }],
        ['change', { protocol: 1, streamId: 's1', seq: 2, key: 'ui', entry: {
          status: 'ready', hasValue: true, value: { announcement: 456 }, contentHash: 'sha256:bad2',
        } }],
      ]),
    })
    const seen = []
    config.subscribe('ui', state => seen.push(state))
    await waitFor(() => seen.some(state => state.status === 'invalid'))
    await waitFor(() => seen.filter(state => state.status === 'invalid').length >= 2)

    assert.equal(warnings.length, 1)
    assert.match(warnings[0], /^\[nacos-web-config\] Decoder rejected configuration key: ui$/)
    const invalid = seen.filter(state => state.status === 'invalid')
    assert.ok(invalid.length >= 2)
    assert.equal(invalid[0].stale, true)
    assert.deepEqual(invalid[0].value, { announcement: 'ok' })
    config.close()
  } finally {
    console.warn = originalWarn
  }
})
