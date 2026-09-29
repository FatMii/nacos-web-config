export type ConfigStatus =
  | 'loading'
  | 'ready'
  | 'invalid'
  | 'deleted'
  | 'unavailable'

type WireStatus = Exclude<ConfigStatus, 'loading'>

export type ConfigState<T> = Readonly<{
  value: T | undefined
  source: 'remote' | 'fallback' | 'none'
  status: ConfigStatus
  stale: boolean
  errorCode?: string
}>

export type ConfigDefinition<T> = Readonly<{
  /** Optional typed guard; omitted, the validated JSON object passes through unchanged. */
  decode?: (value: unknown) => T
  fallback?: T
}>

/** Value type a definition produces: decode's return, else fallback's type, else unknown. */
export type Decoded<Def> =
  Def extends { decode: (value: never) => infer R } ? R
    : Def extends { fallback: infer F } ? F
    : unknown

/** Matches the starter's default `path` + fixed stream suffix. */
export const DEFAULT_ENDPOINT = '/_web-config/v1/stream'

export type WebConfigOptions<Definitions extends Record<string, ConfigDefinition<unknown>>> =
  Readonly<{
    /** Defaults to {@link DEFAULT_ENDPOINT}; pass only when the starter overrides `path`. */
    endpoint?: string
    definitions: Definitions
    fetch?: typeof globalThis.fetch
    credentials?: RequestCredentials
    onError?: (error: WebConfigError) => void
  }>

export type WebConfigClient<Definitions extends Record<string, ConfigDefinition<unknown>>> =
  Readonly<{
    get<Key extends keyof Definitions>(key: Key): ConfigState<Decoded<Definitions[Key]>>
    subscribe<Key extends keyof Definitions>(
      key: Key,
      listener: (state: ConfigState<Decoded<Definitions[Key]>>) => void,
    ): () => void
    close(): void
  }>

export class WebConfigError extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.name = 'WebConfigError'
    this.code = code
  }
}

/** Creates a lazy browser configuration client. Calling get never starts network activity. */
export function createWebConfig<
  Definitions extends Record<string, ConfigDefinition<unknown>>,
>(input: WebConfigOptions<Definitions>): WebConfigClient<Definitions> {
  const options: WebConfigOptions<Definitions> = input.endpoint === undefined
    ? { ...input, endpoint: DEFAULT_ENDPOINT }
    : input
  const states = new Map<keyof Definitions, ConfigState<unknown>>()
  const listeners = new Map<keyof Definitions, Set<(state: ConfigState<unknown>) => void>>()
  const pageDocument = typeof document === 'undefined' ? undefined : document
  let connectionStarted = false
  let closed = false
  let visibilityListenerInstalled = false
  let stopConnection = () => {}
  let retryTimer: ReturnType<typeof setTimeout> | undefined
  let healthyTimer: ReturnType<typeof setTimeout> | undefined
  let retryAttempt = 0
  let generation = 0
  let lastByteAt = 0
  let streamId: string | undefined
  let lastSeq = -1

  const getDefinition = (key: keyof Definitions): ConfigDefinition<unknown> => {
    const definition = options.definitions[key]
    if (definition === undefined) throw new Error(`Unknown configuration key: ${String(key)}`)
    return definition
  }

  for (const key of Object.keys(options.definitions) as Array<keyof Definitions>) {
    const definition = getDefinition(key)
    const hasFallback = Object.prototype.hasOwnProperty.call(definition, 'fallback')
    states.set(key, freezeState({
      value: hasFallback ? cloneAndFreeze(definition.fallback) : undefined,
      source: hasFallback ? 'fallback' : 'none',
      status: 'loading',
      stale: true,
    }))
    listeners.set(key, new Set())
  }

  const get = <Key extends keyof Definitions>(key: Key) => {
    const state = states.get(key)
    if (state === undefined) throw new Error(`Unknown configuration key: ${String(key)}`)
    return state as ConfigState<Decoded<Definitions[Key]>>
  }

  const warnedDecoderFailures = new Set<string>()
  const reportError = (error: WebConfigError) => {
    if (options.onError !== undefined) {
      try {
        options.onError(error)
      } catch {
        // Diagnostics supplied by the consumer must not alter connection or state handling.
      }
      return
    }
    // Without an onError hook the only remaining channel is the console; warn once
    // per key so a rejected decoder never fails silently.
    if (error.code === 'DECODER_ERROR' && !warnedDecoderFailures.has(error.message)) {
      warnedDecoderFailures.add(error.message)
      console.warn(`[nacos-web-config] ${error.message}`)
    }
  }

  const publish = (key: keyof Definitions, state: ConfigState<unknown>) => {
    states.set(key, state)
    for (const subscriber of listeners.get(key) ?? []) {
      try {
        subscriber(state)
      } catch {
        // A consumer callback must not prevent other consumers from observing state.
      }
    }
  }

  const fallbackState = (
    key: keyof Definitions,
    status: ConfigStatus,
    stale: boolean,
    errorCode?: string,
  ): ConfigState<unknown> => {
    const definition = getDefinition(key)
    const hasFallback = Object.prototype.hasOwnProperty.call(definition, 'fallback')
    return freezeState({
      value: hasFallback ? cloneAndFreeze(definition.fallback) : undefined,
      source: hasFallback ? 'fallback' : 'none',
      status,
      stale,
      ...(errorCode === undefined ? {} : { errorCode }),
    })
  }

  const applyEntry = (key: keyof Definitions, value: unknown) => {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
      throw new Error('Invalid wire entry')
    }
    const entry = value as Record<string, unknown>
    const status = entry.status
    if (!isWireStatus(status)) {
      throw new Error('Invalid wire status')
    }
    const hasValue = entry.hasValue
    if (typeof hasValue !== 'boolean') throw new Error('Invalid hasValue flag')
    if (hasValue) {
      if (!isJsonObject(entry.value) || !isContentHash(entry.contentHash)) {
        throw new Error('Invalid valued entry')
      }
    } else if (entry.value !== undefined || entry.contentHash !== undefined) {
      throw new Error('Invalid valueless entry')
    }
    if (status === 'ready' && !hasValue) throw new Error('Ready entry has no value')
    if (status === 'deleted') {
      if (hasValue) throw new Error('Invalid deleted entry')
      publish(key, fallbackState(key, 'deleted', false))
      return
    }

    const current = states.get(key)!
    let decoded: unknown
    let decodedSuccessfully = false
    if (entry.hasValue === true && entry.value !== undefined) {
      try {
        const definition = getDefinition(key)
        decoded = cloneAndFreeze(
          definition.decode === undefined ? entry.value : definition.decode(entry.value),
        )
        decodedSuccessfully = true
      } catch {
        reportError(new WebConfigError(
          'DECODER_ERROR',
          `Decoder rejected configuration key: ${String(key)}`,
        ))
        if (status === 'ready') {
          const retained = current.source === 'remote'
            ? freezeState({ ...current, status: 'invalid', stale: true, errorCode: 'DECODER_ERROR' })
            : fallbackState(key, 'invalid', true, 'DECODER_ERROR')
          publish(key, retained)
          return
        }
      }
    } else if (entry.hasValue !== false) {
      throw new Error('Invalid hasValue flag')
    }

    if (status === 'ready') {
      if (!decodedSuccessfully) throw new Error('Ready entry has no decodable value')
      publish(key, freezeState({
        value: decoded,
        source: 'remote',
        status: 'ready',
        stale: false,
      }))
      return
    }

    const errorCode = typeof entry.errorCode === 'string' ? entry.errorCode : undefined
    if (current.source === 'remote') {
      publish(key, freezeState({ ...current, status, stale: true, ...(errorCode ? { errorCode } : {}) }) as ConfigState<unknown>)
    } else if (decodedSuccessfully) {
      publish(key, freezeState({
        value: decoded,
        source: 'remote',
        status,
        stale: true,
        ...(errorCode ? { errorCode } : {}),
      }) as ConfigState<unknown>)
    } else {
      publish(key, fallbackState(key, status, true, errorCode))
    }
  }

  const acceptProtocolEvent = (event: string, data: string) => {
    if (closed) return
    const message = JSON.parse(data) as Record<string, unknown>
    if (message.protocol !== 1 || typeof message.streamId !== 'string') {
      throw new Error('Invalid event envelope')
    }
    if (event === 'snapshot') {
      if (message.seq !== 0) throw new Error('Invalid snapshot sequence')
      const entries = message.entries
      if (typeof entries !== 'object' || entries === null || Array.isArray(entries)) {
        throw new Error('Invalid snapshot entries')
      }
      const expectedKeys = Object.keys(options.definitions).sort()
      const actualKeys = Object.keys(entries).sort()
      if (JSON.stringify(actualKeys) !== JSON.stringify(expectedKeys)) {
        throw new Error('Snapshot keys do not match definitions')
      }
      streamId = message.streamId
      lastSeq = 0
      for (const entryKey of expectedKeys) {
        applyEntry(entryKey, (entries as Record<string, unknown>)[entryKey])
      }
      return
    }
    if (event === 'change') {
      if (message.streamId !== streamId
          || typeof message.seq !== 'number'
          || typeof message.key !== 'string'
          || !(message.key in options.definitions)) {
        throw new Error('Invalid change envelope')
      }
      if (message.seq <= lastSeq) return
      if (message.seq !== lastSeq + 1) throw new Error('Invalid change sequence')
      lastSeq = message.seq
      applyEntry(message.key, message.entry)
      return
    }
    throw new Error('Unknown SSE event')
  }

  const markUnavailable = () => {
    for (const key of Object.keys(options.definitions) as Array<keyof Definitions>) {
      const current = states.get(key)!
      const next = current.source === 'remote'
        ? freezeState({
            ...current,
            status: 'unavailable',
            stale: true,
            errorCode: 'SOURCE_UNAVAILABLE',
          })
        : fallbackState(key, 'unavailable', true, 'SOURCE_UNAVAILABLE')
      publish(key, next)
    }
  }

  const connect = () => {
    if (closed) return
    const connectionGeneration = ++generation
    lastByteAt = Date.now()
    let healthCheckScheduled = false
    stopConnection = startConnection(
      options,
      (event, data) => {
        if (closed || connectionGeneration !== generation) return
        acceptProtocolEvent(event, data)
        if (!healthCheckScheduled) {
          healthCheckScheduled = true
          healthyTimer = setTimeout(() => {
            if (!closed && connectionGeneration === generation) retryAttempt = 0
          }, 30_000)
        }
      },
      retryAfterMs => {
        if (closed || connectionGeneration !== generation) return
        if (healthyTimer !== undefined) clearTimeout(healthyTimer)
        markUnavailable()
        const maximumDelay = Math.min(30_000, 1_000 * (2 ** retryAttempt))
        const delay = retryAfterMs ?? (Math.random() * maximumDelay)
        retryAttempt += 1
        retryTimer = setTimeout(connect, delay)
      },
      error => {
        if (connectionGeneration !== generation) return
        if (healthyTimer !== undefined) clearTimeout(healthyTimer)
        reportError(error)
      },
      () => {
        if (connectionGeneration === generation) lastByteAt = Date.now()
      },
    )
  }

  const handleVisibilityChange = () => {
    if (closed
        || !connectionStarted
        || pageDocument?.visibilityState !== 'visible'
        || Date.now() - lastByteAt < 45_000) return
    generation += 1
    if (retryTimer !== undefined) clearTimeout(retryTimer)
    if (healthyTimer !== undefined) clearTimeout(healthyTimer)
    stopConnection()
    markUnavailable()
    connect()
  }

  return Object.freeze({
    get,
    subscribe<Key extends keyof Definitions>(
      key: Key,
      listener: (state: ConfigState<Decoded<Definitions[Key]>>) => void,
    ) {
      if (closed) throw new WebConfigError('CLIENT_CLOSED', 'Web config client is closed')
      const keyListeners = listeners.get(key)
      if (keyListeners === undefined) throw new Error(`Unknown configuration key: ${String(key)}`)
      const erasedListener = listener as (state: ConfigState<unknown>) => void
      keyListeners.add(erasedListener)
      listener(get(key))
      if (!connectionStarted && !closed) {
        connectionStarted = true
        if (pageDocument !== undefined) {
          pageDocument.addEventListener('visibilitychange', handleVisibilityChange)
          visibilityListenerInstalled = true
        }
        connect()
      }
      let active = true
      return () => {
        if (!active) return
        active = false
        keyListeners.delete(erasedListener)
      }
    },
    close() {
      if (closed) return
      closed = true
      generation += 1
      if (retryTimer !== undefined) clearTimeout(retryTimer)
      if (healthyTimer !== undefined) clearTimeout(healthyTimer)
      stopConnection()
      if (visibilityListenerInstalled) {
        pageDocument?.removeEventListener('visibilitychange', handleVisibilityChange)
        visibilityListenerInstalled = false
      }
      for (const keyListeners of listeners.values()) keyListeners.clear()
    },
  })
}

function startConnection<Definitions extends Record<string, ConfigDefinition<unknown>>>(
  options: WebConfigOptions<Definitions>,
  onEvent: (event: string, data: string) => void,
  onDisconnected: (retryAfterMs?: number) => void,
  onFatalError: (error: WebConfigError) => void,
  onActivity: () => void,
): () => void {
  const endpoint = options.endpoint ?? DEFAULT_ENDPOINT
  const separator = endpoint.includes('?') ? '&' : '?'
  const query = Object.keys(options.definitions)
    .sort()
    .map(key => `key=${encodeURIComponent(key)}`)
    .join('&')
  const fetchImplementation = options.fetch ?? globalThis.fetch
  const controller = new AbortController()
  let consuming = false
  let disconnected = false
  let inactivityTimer: ReturnType<typeof setTimeout> | undefined

  const clearInactivityTimer = () => {
    if (inactivityTimer !== undefined) clearTimeout(inactivityTimer)
  }
  const disconnectOnce = (retryAfterMs?: number) => {
    if (disconnected) return
    disconnected = true
    clearInactivityTimer()
    onDisconnected(retryAfterMs)
  }
  const resetInactivityTimer = () => {
    onActivity()
    clearInactivityTimer()
    inactivityTimer = setTimeout(() => {
      if (disconnected || controller.signal.aborted) return
      controller.abort()
      disconnectOnce()
    }, 45_000)
  }

  void fetchImplementation(`${endpoint}${separator}${query}`, {
    credentials: options.credentials ?? 'same-origin',
    headers: { Accept: 'text/event-stream' },
    signal: controller.signal,
  })
    .then(async response => {
      if (response.status === 429) {
        const retryAfterMs = parseRetryAfter(response.headers.get('Retry-After'))
        controller.abort()
        disconnectOnce(retryAfterMs)
        return
      }
      if ([400, 401, 403, 405, 406].includes(response.status)) {
        const error = await readHttpError(response)
        disconnected = true
        clearInactivityTimer()
        controller.abort()
        onFatalError(error)
        return
      }
      if (!response.ok || response.body === null) throw new Error('Stream request failed')
      consuming = true
      resetInactivityTimer()
      await consumeSse(response.body, onEvent, resetInactivityTimer)
      if (!controller.signal.aborted) disconnectOnce()
    })
    .catch(error => {
      if (controller.signal.aborted) return
      if (!consuming) {
        disconnectOnce()
        return
      }
      if (!(error instanceof WebConfigError) || error.code !== 'PROTOCOL_ERROR') {
        controller.abort()
        disconnectOnce()
        return
      }
      disconnected = true
      clearInactivityTimer()
      controller.abort()
      onFatalError(error)
    })
  return () => {
    disconnected = true
    clearInactivityTimer()
    controller.abort()
  }
}

function parseRetryAfter(value: string | null): number | undefined {
  if (value === null) return undefined
  const seconds = Number(value)
  if (Number.isFinite(seconds) && seconds >= 0) return seconds * 1_000
  const timestamp = Date.parse(value)
  if (Number.isNaN(timestamp)) return undefined
  return Math.max(0, timestamp - Date.now())
}

async function readHttpError(response: Response): Promise<WebConfigError> {
  try {
    const body = await response.json() as Record<string, unknown>
    const code = typeof body.code === 'string' ? body.code : `HTTP_${response.status}`
    const message = typeof body.message === 'string'
      ? body.message
      : `Configuration stream request failed with HTTP ${response.status}`
    return new WebConfigError(code, message)
  } catch {
    return new WebConfigError(
      `HTTP_${response.status}`,
      `Configuration stream request failed with HTTP ${response.status}`,
    )
  }
}

async function consumeSse(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: string, data: string) => void,
  onBytes: () => void,
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let text = ''
  let event = ''
  let dataLines: string[] = []

  const acceptLine = (line: string) => {
    if (line === '') {
      if (dataLines.length > 0) {
        try {
          onEvent(event || 'message', dataLines.join('\n'))
        } catch {
          throw new WebConfigError(
            'PROTOCOL_ERROR',
            'The server sent an invalid configuration stream',
          )
        }
      }
      event = ''
      dataLines = []
      return
    }
    if (line.startsWith(':')) return
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    let value = colon < 0 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value
    if (field === 'data') dataLines.push(value)
  }

  const consumeCompleteLines = () => {
    while (true) {
      const carriage = text.indexOf('\r')
      const newline = text.indexOf('\n')
      let end = carriage < 0 ? newline : newline < 0 ? carriage : Math.min(carriage, newline)
      if (end < 0 || (text[end] === '\r' && end === text.length - 1)) return
      const width = text[end] === '\r' && text[end + 1] === '\n' ? 2 : 1
      acceptLine(text.slice(0, end))
      text = text.slice(end + width)
    }
  }

  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      onBytes()
      text += decoder.decode(value, { stream: true })
      consumeCompleteLines()
    }
    text += decoder.decode()
    consumeCompleteLines()
    // An event without a terminating blank line is deliberately not dispatched at EOF.
  } finally {
    reader.releaseLock()
  }
}

function freezeState<T>(state: ConfigState<T>): ConfigState<T> {
  return Object.freeze(state)
}

function isWireStatus(value: unknown): value is WireStatus {
  return value === 'ready'
    || value === 'invalid'
    || value === 'deleted'
    || value === 'unavailable'
}

function isJsonObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isContentHash(value: unknown): value is string {
  return typeof value === 'string' && value.startsWith('sha256:') && value.length > 7
}

function cloneAndFreeze<T>(value: T): T {
  return deepFreeze(structuredClone(value))
}

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value
  for (const child of Object.values(value)) deepFreeze(child)
  return Object.freeze(value)
}
