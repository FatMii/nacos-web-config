#!/usr/bin/env node
/**
 * Obtain a short-lived Nacos 3 Admin API accessToken for local / CI E2E.
 *
 * Default Compose enables admin auth on nacos3. This script logs in as the
 * bootstrap admin user, changes the password once when Nacos requires it, then
 * prints only the accessToken to stdout (no password echo).
 *
 * Env:
 *   NWC_NACOS3_URL           default http://127.0.0.1:28848
 *   NWC_NACOS3_USER          default nacos
 *   NWC_NACOS3_PASSWORD      default nacos (first boot)
 *   NWC_NACOS3_NEW_PASSWORD  password to set when forced to rotate (CI default below)
 */
const base = (process.env.NWC_NACOS3_URL || 'http://127.0.0.1:28848').replace(/\/$/, '')
const user = process.env.NWC_NACOS3_USER || 'nacos'
const password = process.env.NWC_NACOS3_PASSWORD || 'nacos'
const newPassword = process.env.NWC_NACOS3_NEW_PASSWORD || 'NacosWebConfig_CI_ChangeMe1!'
// That fallback is a throwaway credential for the loopback-only Compose container in CI, not a real secret.

function fail(message) {
  console.error(`[fetch-nacos3-admin-token] ${message}`)
  process.exit(1)
}

async function login(pass) {
  const response = await fetch(`${base}/nacos/v3/auth/user/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username: user, password: pass }),
  })
  const text = await response.text()
  let body
  try {
    body = JSON.parse(text)
  } catch {
    fail(`login returned non-JSON (HTTP ${response.status}): ${text.slice(0, 200)}`)
  }
  return { response, body }
}

async function changePassword(token, oldPass, nextPass) {
  const response = await fetch(`${base}/nacos/v3/auth/user/password`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      accessToken: token,
    },
    body: new URLSearchParams({
      username: user,
      newPassword: nextPass,
      // Some Nacos 3 builds also accept oldPassword; harmless if ignored.
      oldPassword: oldPass,
    }),
  })
  const text = await response.text()
  let body
  try {
    body = JSON.parse(text)
  } catch {
    fail(`password change returned non-JSON (HTTP ${response.status}): ${text.slice(0, 200)}`)
  }
  if (!response.ok || body.code !== 0) {
    fail(`password change failed (HTTP ${response.status}): ${text.slice(0, 300)}`)
  }
}

function needsPasswordChange(body) {
  const message = `${body?.message || ''} ${body?.data || ''}`.toLowerCase()
  return message.includes('password') && (message.includes('change')
    || message.includes('expired')
    || message.includes('modify')
    || message.includes('reset'))
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

  let { body } = await login(password)
  if (body?.code === 0 && body?.data?.accessToken) {
    process.stdout.write(String(body.data.accessToken))
    return
  }

  // Already rotated in a persistent volume — try the CI password first.
  ;({ body } = await login(newPassword))
  if (body?.code === 0 && body?.data?.accessToken) {
    process.stdout.write(String(body.data.accessToken))
    return
  }

  // First-boot path: login may return a temporary token plus a "must change password" signal,
  // or a non-zero code. Attempt rotation then re-login.
  const tempToken = body?.data?.accessToken
  if (tempToken || needsPasswordChange(body) || body?.code !== 0) {
    if (!tempToken) {
      // Last resort: some builds require login with default then password API without prior token.
      const retry = await login(password)
      if (!retry.body?.data?.accessToken) {
        fail(`unable to obtain bootstrap token: ${JSON.stringify(retry.body)}`)
      }
      await changePassword(retry.body.data.accessToken, password, newPassword)
    } else {
      await changePassword(tempToken, password, newPassword)
    }
    const after = await login(newPassword)
    if (after.body?.code === 0 && after.body?.data?.accessToken) {
      process.stdout.write(String(after.body.data.accessToken))
      return
    }
    fail(`login after password change failed: ${JSON.stringify(after.body)}`)
  }

  fail(`unexpected login response: ${JSON.stringify(body)}`)
}

main().catch(error => fail(error.stack || String(error)))
