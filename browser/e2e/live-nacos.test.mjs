import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { promisify } from 'node:util'

import { chromium } from 'playwright-core'
import { createWebConfig } from '../packages/browser/dist/index.js'

const host = process.env.NWC_E2E_HOST
const nacos = process.env.NWC_E2E_NACOS
const nacosApi = process.env.NWC_E2E_NACOS_API ?? 'v1'
const nacosToken = process.env.NWC_E2E_NACOS_TOKEN
const nacosContainer = process.env.NWC_E2E_DOCKER_CONTAINER
const browserExecutable = process.env.NWC_E2E_BROWSER_EXECUTABLE
const browserRequired = process.env.npm_lifecycle_event === 'test:e2e:browser'
const hostJar = process.env.NWC_E2E_HOST_JAR
const javaExecutable = process.env.NWC_E2E_JAVA
const restartOnly = process.env.NWC_E2E_RESTART_ONLY === 'true'
const proxyOnly = process.env.NWC_E2E_PROXY_ONLY === 'true'
const dataId = 'web-demo.public.json'
const group = 'WEB_DEMO'

test('Browser SDK preserves LKG across invalid Nacos content and clears it on deletion', { skip: restartOnly || proxyOnly }, async t => {
  assert.ok(host, 'NWC_E2E_HOST is required')
  assert.ok(nacos, 'NWC_E2E_NACOS is required')
  if (browserRequired) assert.ok(browserExecutable, 'NWC_E2E_BROWSER_EXECUTABLE is required for test:e2e:browser')
  assert.equal(Boolean(hostJar), Boolean(javaExecutable), 'NWC_E2E_HOST_JAR and NWC_E2E_JAVA must be set together')
  assert.match(nacosApi, /^(v1|v3)$/)
  if (nacosApi === 'v3') assert.ok(nacosToken, 'NWC_E2E_NACOS_TOKEN is required for the v3 Admin API')
  const first = configValue(`initial-${Date.now()}`)
  const second = configValue(`updated-${Date.now()}`)
  const sequenceA = configValue(`sequence-a-${Date.now()}`)
  const sequenceB = configValue(`sequence-b-${Date.now()}`)
  const sequenceC = configValue(`sequence-c-${Date.now()}`)
  const recovered = configValue(`recovered-${Date.now()}`)
  let hostProcess
  let containerStopped = false
  let browser
  let client
  t.after(async () => {
    await browser?.close()
    client?.close()
    await stopHost(hostProcess)
    if (containerStopped) {
      await docker('start', nacosContainer)
      await delay(5_000)
    }
    await removeEventually(30_000)
  })

  hostProcess = hostJar ? await startHost() : undefined
  await publish(first)

  const errors = []
  client = createWebConfig({
    endpoint: `${host}/_web-config/v1/stream`,
    definitions: { ui: { fallback: configValue('fallback'), decode: decodeUi } },
    onError: error => errors.push(error),
  })
  const seen = []
  client.subscribe('ui', state => seen.push(state))
  await waitFor(() => client.get('ui').value?.banner.text === first.banner.text)

  let browserPage
  if (browserExecutable) {
    const fixture = await readFile(new URL('./live-browser.html', import.meta.url), 'utf8')
    const sdk = await readFile(new URL('../packages/browser/dist/index.js', import.meta.url), 'utf8')
    browser = await chromium.launch({ executablePath: browserExecutable, headless: true })
    const context = await browser.newContext()
    await context.route('**/__nwc_e2e__/browser.html', route => route.fulfill({
      status: 200, contentType: 'text/html; charset=utf-8', body: fixture,
    }))
    await context.route('**/__nwc_e2e__/sdk.js', route => route.fulfill({
      status: 200, contentType: 'text/javascript; charset=utf-8', body: sdk,
    }))
    browserPage = await context.newPage()
    await browserPage.goto(`${host}/__nwc_e2e__/browser.html`)
    await waitForBrowserState(browserPage, 'ready', first.banner.text)
  }

  await publish(second)
  await waitFor(() => client.get('ui').value?.banner.text === second.banner.text)
  if (browserPage) await waitForBrowserState(browserPage, 'ready', second.banner.text)

  await delay(500)
  const duplicateCount = seen.filter(state => state.status === 'ready'
    && state.value?.banner.text === second.banner.text).length
  await publish(second)
  await delay(1_500)
  assert.equal(seen.filter(state => state.status === 'ready'
    && state.value?.banner.text === second.banner.text).length, duplicateCount)

  await publish(sequenceA)
  await publish(sequenceB)
  await publish(sequenceC)
  await waitFor(() => client.get('ui').value?.banner.text === sequenceC.banner.text)
  if (browserPage) await waitForBrowserState(browserPage, 'ready', sequenceC.banner.text)
  await delay(1_500)
  assert.equal(client.get('ui').value.banner.text, sequenceC.banner.text)

  await publishContent(JSON.stringify({
    banner: { enabled: true, text: 42 },
    refreshIntervalMs: 30_000,
  }))
  await waitFor(() => client.get('ui').errorCode === 'DECODER_ERROR')
  assert.deepEqual(client.get('ui'), {
    value: sequenceC,
    source: 'remote',
    status: 'invalid',
    stale: true,
    errorCode: 'DECODER_ERROR',
  })
  if (browserPage) {
    await waitForBrowserState(browserPage, 'invalid', sequenceC.banner.text)
    assert.equal(await browserPage.getAttribute('body', 'data-error'), 'DECODER_ERROR')
  }

  await publishContent('{"broken":')
  await waitFor(() => client.get('ui').status === 'invalid'
    && client.get('ui').errorCode === 'INVALID_JSON')
  if (browserPage) await waitForBrowserState(browserPage, 'invalid', sequenceC.banner.text)
  assert.deepEqual(client.get('ui'), {
    value: sequenceC,
    source: 'remote',
    status: 'invalid',
    stale: true,
    errorCode: 'INVALID_JSON',
  })

  if (nacosContainer) {
    await publish(second)
    await waitFor(() => client.get('ui').status === 'ready')
    await docker('stop', '--time', '10', nacosContainer)
    containerStopped = true
    await waitFor(() => client.get('ui').status === 'unavailable', 90_000)
    if (browserPage) await waitForBrowserState(browserPage, 'unavailable', second.banner.text, 90_000)
    assert.equal(client.get('ui').value.banner.text, second.banner.text)
    assert.equal(client.get('ui').stale, true)

    await docker('start', nacosContainer)
    containerStopped = false
    await publishEventually(recovered, 90_000)
    await waitFor(
      () => client.get('ui').status === 'ready'
        && client.get('ui').value?.banner.text === recovered.banner.text,
      90_000,
    )
    if (browserPage) await waitForBrowserState(browserPage, 'ready', recovered.banner.text, 90_000)
  }

  await remove()
  await waitFor(() => client.get('ui').status === 'deleted')
  if (browserPage) await waitForBrowserState(browserPage, 'deleted', 'fallback')

  await publishContent('{"still-broken":')
  await waitFor(() => client.get('ui').status === 'invalid'
    && client.get('ui').errorCode === 'INVALID_JSON')
  assert.deepEqual(client.get('ui'), {
    value: configValue('fallback'),
    source: 'fallback',
    status: 'invalid',
    stale: true,
    errorCode: 'INVALID_JSON',
  })
  if (browserPage) await waitForBrowserState(browserPage, 'invalid', 'fallback')

  await remove()
  await waitFor(() => client.get('ui').status === 'deleted')

  assert.equal(errors.length, 1)
  assert.equal(errors[0].code, 'DECODER_ERROR')
  assert.deepEqual(client.get('ui'), {
    value: configValue('fallback'), source: 'fallback', status: 'deleted', stale: false,
  })
  assert.ok(seen.some(state => state.status === 'ready' && state.value.banner.text === first.banner.text))
  assert.ok(seen.some(state => state.status === 'ready' && state.value.banner.text === second.banner.text))
  if (nacosContainer) {
    assert.ok(seen.some(state => state.status === 'unavailable'))
    assert.ok(seen.some(state => state.status === 'ready' && state.value.banner.text === recovered.banner.text))
  }
})

test('Chrome reconnects to a restarted host and accepts its authoritative snapshot', { skip: !restartOnly }, async t => {
  assert.ok(host, 'NWC_E2E_HOST is required')
  assert.ok(nacos, 'NWC_E2E_NACOS is required')
  assert.ok(hostJar, 'NWC_E2E_HOST_JAR is required')
  assert.ok(javaExecutable, 'NWC_E2E_JAVA is required')
  assert.ok(browserExecutable, 'NWC_E2E_BROWSER_EXECUTABLE is required')

  const before = configValue(`before-restart-${Date.now()}`)
  const after = configValue(`after-restart-${Date.now()}`)
  let hostProcess
  let browser
  let client
  t.after(async () => {
    await browser?.close()
    client?.close()
    await stopHost(hostProcess)
    await removeEventually(30_000)
  })

  await publish(before)
  hostProcess = await startHost()
  const errors = []
  client = createWebConfig({
    endpoint: `${host}/_web-config/v1/stream`,
    definitions: { ui: { fallback: configValue('fallback'), decode: decodeUi } },
    onError: error => errors.push(error),
  })
  client.subscribe('ui', () => {})
  await waitFor(() => client.get('ui').status === 'ready'
    && client.get('ui').value?.banner.text === before.banner.text)

  const fixture = await readFile(new URL('./live-browser.html', import.meta.url), 'utf8')
  const sdk = await readFile(new URL('../packages/browser/dist/index.js', import.meta.url), 'utf8')
  browser = await chromium.launch({ executablePath: browserExecutable, headless: true })
  const context = await browser.newContext()
  await context.route('**/__nwc_e2e__/browser.html', route => route.fulfill({
    status: 200, contentType: 'text/html; charset=utf-8', body: fixture,
  }))
  await context.route('**/__nwc_e2e__/sdk.js', route => route.fulfill({
    status: 200, contentType: 'text/javascript; charset=utf-8', body: sdk,
  }))
  const page = await context.newPage()
  await page.goto(`${host}/__nwc_e2e__/browser.html`)
  await waitForBrowserState(page, 'ready', before.banner.text)

  await stopHost(hostProcess)
  hostProcess = undefined
  await publish(after)

  hostProcess = await startHost()
  await waitFor(() => client.get('ui').status === 'ready'
    && client.get('ui').value?.banner.text === after.banner.text, 90_000)
  await waitForBrowserState(page, 'ready', after.banner.text, 90_000)
  assert.deepEqual(errors, [])
})

test('Nginx forwards snapshots, heartbeats, and updates without buffering', { skip: !proxyOnly }, async t => {
  assert.ok(host, 'NWC_E2E_HOST is required')
  assert.ok(nacos, 'NWC_E2E_NACOS is required')
  assert.ok(hostJar, 'NWC_E2E_HOST_JAR is required')
  assert.ok(javaExecutable, 'NWC_E2E_JAVA is required')
  assert.ok(browserExecutable, 'NWC_E2E_BROWSER_EXECUTABLE is required')

  const first = configValue(`proxy-initial-${Date.now()}`)
  const updated = configValue(`proxy-updated-${Date.now()}`)
  let hostProcess
  let browser
  let client
  t.after(async () => {
    await browser?.close()
    client?.close()
    await stopHost(hostProcess)
    await removeEventually(30_000)
  })

  await publish(first)
  hostProcess = await startHost()

  const controller = new AbortController()
  const response = await fetch(`${host}/_web-config/v1/stream?key=ui`, {
    headers: { Accept: 'text/event-stream' },
    signal: controller.signal,
  })
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('x-accel-buffering'), 'no')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const startedAt = Date.now()
  let snapshotAt
  let heartbeatAt
  let body = ''
  try {
    while (!heartbeatAt && Date.now() - startedAt < 10_000) {
      const { done, value } = await reader.read()
      if (done) break
      body += decoder.decode(value, { stream: true })
      if (!snapshotAt && /event:\s*snapshot/.test(body)) snapshotAt = Date.now()
      if (body.includes(':ping') || body.includes(': ping')) heartbeatAt = Date.now()
    }
  } finally {
    controller.abort()
  }
  assert.ok(snapshotAt, 'snapshot must arrive through Nginx')
  assert.ok(heartbeatAt, 'heartbeat must arrive through Nginx')
  assert.ok(snapshotAt - startedAt < 2_000, 'snapshot must not be buffered')
  assert.ok(heartbeatAt - snapshotAt < 5_000, 'heartbeat must arrive near its configured interval')

  const errors = []
  client = createWebConfig({
    endpoint: `${host}/_web-config/v1/stream`,
    definitions: { ui: { fallback: configValue('fallback'), decode: decodeUi } },
    onError: error => errors.push(error),
  })
  client.subscribe('ui', () => {})
  await waitFor(() => client.get('ui').value?.banner.text === first.banner.text)

  const fixture = await readFile(new URL('./live-browser.html', import.meta.url), 'utf8')
  const sdk = await readFile(new URL('../packages/browser/dist/index.js', import.meta.url), 'utf8')
  browser = await chromium.launch({ executablePath: browserExecutable, headless: true })
  const context = await browser.newContext()
  await context.route('**/__nwc_e2e__/browser.html', route => route.fulfill({
    status: 200, contentType: 'text/html; charset=utf-8', body: fixture,
  }))
  await context.route('**/__nwc_e2e__/sdk.js', route => route.fulfill({
    status: 200, contentType: 'text/javascript; charset=utf-8', body: sdk,
  }))
  const page = await context.newPage()
  await page.goto(`${host}/__nwc_e2e__/browser.html`)
  await waitForBrowserState(page, 'ready', first.banner.text)

  const updateStartedAt = Date.now()
  await publish(updated)
  await waitFor(() => client.get('ui').value?.banner.text === updated.banner.text)
  await waitForBrowserState(page, 'ready', updated.banner.text)
  assert.ok(Date.now() - updateStartedAt < 5_000, 'configuration update must not be buffered')
  assert.deepEqual(errors, [])
})

function configValue(text) {
  return { banner: { enabled: true, text }, refreshIntervalMs: 30_000 }
}

function decodeUi(value) {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('ui must be an object')
  const banner = Reflect.get(value, 'banner')
  const refreshIntervalMs = Reflect.get(value, 'refreshIntervalMs')
  if (typeof banner !== 'object' || banner === null || Array.isArray(banner)
      || typeof Reflect.get(banner, 'enabled') !== 'boolean'
      || typeof Reflect.get(banner, 'text') !== 'string'
      || !Number.isInteger(refreshIntervalMs)) throw new Error('invalid ui config')
  return {
    banner: { enabled: Reflect.get(banner, 'enabled'), text: Reflect.get(banner, 'text') },
    refreshIntervalMs,
  }
}

async function publish(value) {
  await publishContent(JSON.stringify(value))
}

async function publishContent(content) {
  const v3 = nacosApi === 'v3'
  const response = await fetch(`${nacos}/nacos/${v3 ? 'v3/admin/cs/config' : 'v1/cs/configs'}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      ...(v3 ? { accessToken: nacosToken } : {}),
    },
    body: new URLSearchParams(v3
      ? { namespaceId: 'public', dataId, groupName: group, content, type: 'json' }
      : { dataId, group, content, type: 'json' }),
  })
  await assertNacosSuccess(response, 'publish', v3)
}

async function publishEventually(value, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  let lastError
  while (Date.now() < deadline) {
    try {
      await publish(value)
      return
    } catch (error) {
      lastError = error
      await new Promise(resolve => setTimeout(resolve, 1_000))
    }
  }
  throw lastError
}

async function removeEventually(timeoutMs) {
  const deadline = Date.now() + timeoutMs
  let lastError
  while (Date.now() < deadline) {
    try {
      await remove()
      return
    } catch (error) {
      lastError = error
      await new Promise(resolve => setTimeout(resolve, 1_000))
    }
  }
  throw lastError
}

async function remove() {
  const v3 = nacosApi === 'v3'
  const url = new URL(`${nacos}/nacos/${v3 ? 'v3/admin/cs/config' : 'v1/cs/configs'}`)
  url.search = new URLSearchParams(v3
    ? { namespaceId: 'public', dataId, groupName: group }
    : { dataId, group }).toString()
  const response = await fetch(url, {
    method: 'DELETE',
    headers: v3 ? { accessToken: nacosToken } : {},
  })
  await assertNacosSuccess(response, 'delete', v3)
}

async function assertNacosSuccess(response, operation, v3) {
  const body = await response.text()
  assert.equal(response.ok, true, `Nacos ${operation} failed with HTTP ${response.status}: ${body}`)
  if (v3) {
    const result = JSON.parse(body)
    assert.equal(result.code, 0, `Nacos ${operation} returned code ${result.code}`)
    assert.equal(result.data, true)
  } else {
    assert.equal(body, 'true')
  }
}

async function waitFor(condition, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await condition()) return
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  assert.fail(`condition was not reached within ${timeoutMs}ms`)
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

async function waitForBrowserState(page, status, text, timeoutMs = 20_000) {
  await page.waitForFunction(
    ({ expectedStatus, expectedText }) => document.body.dataset.status === expectedStatus
      && document.querySelector('#banner')?.textContent === expectedText,
    { expectedStatus: status, expectedText: text },
    { timeout: timeoutMs },
  )
}

const execFileAsync = promisify(execFile)

async function docker(...args) {
  await execFileAsync('docker', args, { windowsHide: true })
}

async function startHost() {
  const child = spawn(javaExecutable, ['-jar', hostJar], {
    env: process.env,
    stdio: 'ignore',
    windowsHide: true,
  })
  try {
    await waitFor(async () => {
      if (child.exitCode !== null) throw new Error(`managed host exited with code ${child.exitCode}`)
      try {
        return (await fetch(`${host}/demo`)).ok
      } catch {
        return false
      }
    }, 90_000)
    return child
  } catch (error) {
    await stopHost(child)
    throw error
  }
}

async function stopHost(child) {
  if (!child || child.exitCode !== null) return
  if (process.platform === 'win32') {
    await execFileAsync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true })
    await delay(500)
    return
  }
  const exited = new Promise(resolve => child.once('exit', resolve))
  child.kill('SIGTERM')
  await Promise.race([
    exited,
    delay(15_000).then(() => { throw new Error('managed host did not stop within 15000ms') }),
  ])
}
