#!/usr/bin/env node
/**
 * Obtain a short-lived Nacos 3 Admin API accessToken for local / CI E2E.
 *
 * A fresh Nacos 3 deployment has no administrator at all, so /v3/auth/user/login only answers
 * with a token once POST /v3/auth/user/admin has created one. This script logs in when an
 * administrator already exists, otherwise it initializes one with the CI password, then prints
 * only the accessToken to stdout (no password echo).
 *
 * Env:
 *   NWC_NACOS3_URL           default http://127.0.0.1:28848
 *   NWC_NACOS3_USER          default nacos
 *   NWC_NACOS3_PASSWORD      password of an administrator that already exists (first choice)
 *   NWC_NACOS3_NEW_PASSWORD  password used when this run has to initialize the administrator
 */
const base = (process.env.NWC_NACOS3_URL || 'http://127.0.0.1:28848').replace(/\/$/, '')
const user = process.env.NWC_NACOS3_USER || 'nacos'
const existingPassword = process.env.NWC_NACOS3_PASSWORD || 'nacos'
// Throwaway credential for the loopback-only Compose container in CI, not a real secret.
const bootstrapPassword = process.env.NWC_NACOS3_NEW_PASSWORD || 'NacosWebConfig_CI_ChangeMe1!'

function fail(message) {
  console.error(`[fetch-nacos3-admin-token] ${message}`)
  process.exit(1)
}

function describe(text) {
  const oneLine = text.replace(/\s+/g, ' ').trim()
  return oneLine.length > 200 ? `${oneLine.slice(0, 200)}...` : oneLine
}

async function form(path, params) {
  const response = await fetch(`${base}/nacos/${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params),
  })
  const text = await response.text()
  let json
  try {
    json = JSON.parse(text)
  } catch {
    // Nacos answers a rejected login with plain text, not a JSON envelope.
  }
  return { status: response.status, text, json }
}

/** Login answers a bare {accessToken,...} map on success and something else on failure. */
async function login(password) {
  const { json } = await form('v3/auth/user/login', { username: user, password })
  const token = json?.accessToken ?? json?.data?.accessToken
  return token ? String(token) : undefined
}

/** Creates the administrator, or reports that one already exists (HTTP 409). */
async function initializeAdmin(password) {
  const { status, text, json } = await form('v3/auth/user/admin', { password })
  if (status === 409) {
    return { initialized: false }
  }
  if (status !== 200 || json?.code !== 0) {
    fail(`admin initialization failed (HTTP ${status}): ${describe(text)}`)
  }
  return { initialized: true }
}

async function waitReady(timeoutMs = 180_000) {
  const deadline = Date.now() + timeoutMs
  let lastError
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${base}/nacos/`)
      if (response.ok || response.status === 404 || response.status === 302) return
      lastError = `HTTP ${response.status}`
    } catch (error) {
      lastError = error.message || String(error)
    }
    await new Promise(resolve => setTimeout(resolve, 2_000))
  }
  fail(`Nacos 3 at ${base} not ready within ${timeoutMs}ms (${lastError})`)
}

async function main() {
  await waitReady()

  for (const password of [existingPassword, bootstrapPassword]) {
    const token = await login(password)
    if (token) {
      process.stdout.write(token)
      return
    }
  }

  const { initialized } = await initializeAdmin(bootstrapPassword)
  if (!initialized) {
    fail('an administrator already exists but none of the tried passwords match it; '
      + 'set NWC_NACOS3_PASSWORD to that administrator password')
  }
  const token = await login(bootstrapPassword)
  if (!token) {
    fail('login still fails immediately after initializing the administrator')
  }
  process.stdout.write(token)
}

main().catch(error => fail(error.stack || String(error)))
