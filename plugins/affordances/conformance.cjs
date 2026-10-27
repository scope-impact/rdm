// The conformance check: holds the served design to the affordance contract
// (dhf/spec/affordances.yaml, its "conformance" list), black box, from "/".
// It is the judge, kept in dhf/harness, so the builder never writes it.
// Run by probe.sh: CONTRACT and BINDING hold the contract and the binding as
// JSON; tokens are dhf/spec/tokens.json. Exit 0 every rule holds, 1 some rule
// fails, 2 it cannot run.
// The research behind it: rdm's dhf/raw/affordance-contracts.md.
const fs = require('node:fs')
const { chromium } = require('playwright')

const base = process.argv[2].replace(/\/+$/, '')  // verify passes the url with its trailing slash
const contract = JSON.parse(process.env.CONTRACT)
const binding = JSON.parse(process.env.BINDING)
const tokens = JSON.parse(fs.readFileSync('dhf/spec/tokens.json', 'utf8'))
const R = binding.recognise
const controls = ['textbox', 'select', 'checkbox', 'button'].map((c) => R[c]).join(', ')
const toolOf = Object.fromEntries(Object.entries(contract.actions).map(([id, a]) => [a.tool.name, id]))
const results = []
const rule = (name, ok, detail) => results.push({ name, ok: !!ok, detail })
const fill = (t, args) => t.replace(/\{(\w+)\}/g, (_, k) => encodeURIComponent(String(args[k] ?? '')))

async function main() {
  const browser = await chromium.launch()
  const page = await browser.newPage()
  await page.addInitScript(() => {
    window.__tools = []
    navigator.modelContext = {
      registerTool: (t) => window.__tools.push(t),
      provideContext: (c) => window.__tools.push(...((c && c.tools) || [])),
    }
  })
  const go = async (path) => { await page.goto(base + path); await page.waitForLoadState('networkidle') }
  const scopeOf = async (tool) => {
    const card = page.locator(R.card, { has: page.locator(`form[toolname="${tool}"]`) })
    return (await card.count()) ? card.first() : page.locator(`form[toolname="${tool}"]`)
  }

  // Every screen, reached from "/" by links only.
  const reached = new Set(), todo = ['/']
  while (todo.length) {
    const path = todo.shift(); if (reached.has(path)) continue
    reached.add(path); await go(path)
    todo.push(...(await page.$$eval('a[href^="/"]', (as) => as.map((a) => a.getAttribute('href').split('#')[0].split('?')[0]))))
    const screenId = Object.keys(contract.screens).find((id) => contract.screens[id].path === path)
    if (!screenId) continue
    const screen = contract.screens[screenId]
    const offered = screen.offers.map((id) => contract.actions[id].tool.name).sort()
    const forms = (await page.$$eval('form[toolname]', (fs) => fs.map((f) => f.getAttribute('toolname')))).sort()
    rule(`${screenId}: offers its actions as forms`, JSON.stringify([...new Set(forms)]) === JSON.stringify(offered), `forms ${forms}; contract ${offered}`)
    rule(`${screenId}: no undeclared toolname`, forms.every((t) => toolOf[t]), forms.filter((t) => !toolOf[t]).join(', ') || 'none')
    const registered = [...new Set(await page.evaluate(() => window.__tools.map((t) => t.name)))].sort()
    rule(`${screenId}: WebMCP registers exactly its actions`, JSON.stringify(registered) === JSON.stringify(offered), `registered ${registered}`)
    rule(`${screenId}: must heading "${screen.must.heading}"`, await page.getByRole('heading', { level: 1, name: screen.must.heading }).count() === 1, screen.must.heading)
    for (const b of screen.must.buttons ?? [])
      rule(`${screenId}: must button "${b}"`, await page.getByRole('button', { name: b, exact: true }).count() >= 1, b)

    for (const id of screen.offers) {
      const action = contract.actions[id], form = page.locator(`form[toolname="${action.tool.name}"]`).first()
      const missing = [], wrong = []
      for (const [name, p] of Object.entries(action.input)) {
        if (p.component === 'checkbox-group') {
          const g = form.getByRole('group', { name: p.label })
          if (!(await g.count())) { missing.push(p.label); continue }
          if (!(await g.first().evaluate((el, [grp, box]) => el.matches(grp) &&
              [...el.querySelectorAll('input[type=checkbox], [role=checkbox]')].every((c) => c.matches(box) || c.getAttribute('aria-hidden') === 'true'),
              [R['checkbox-group'], R.checkbox]))) wrong.push(`${p.label} is not a checkbox-group`)
        } else {
          const c = form.getByLabel(p.label, { exact: true })
          if (!(await c.count())) { missing.push(p.label); continue }
          if (!(await c.first().evaluate((el, sel) => el.matches(sel), R[p.component]))) wrong.push(`${p.label} is not a ${p.component}`)
        }
      }
      rule(`${screenId}/${action.tool.name}: every input has its accessible label`, !missing.length, missing.join(', ') || 'all')
      rule(`${screenId}/${action.tool.name}: each input is its declared component`, !wrong.length, wrong.join('; ') || 'all')
      const rogue = await form.evaluate((f, [card, controls]) => {
        const bad = [], host = f.closest(card)
        if (!host) bad.push('the form is not inside a card')
        for (const el of f.querySelectorAll('input, select, textarea, button, [role="checkbox"]')) {
          if (el.getAttribute('type') === 'hidden' || (el.tagName === 'INPUT' && el.getAttribute('aria-hidden') === 'true')) continue
          if (!el.matches(controls)) bad.push(`<${el.tagName.toLowerCase()} name="${el.getAttribute('name') ?? ''}"> is not the design system's`)
        }
        for (const el of [host ?? f, ...(host ?? f).querySelectorAll('*')]) {
          if (el.getAttribute('style') && /(color|background|border)\s*:/i.test(el.getAttribute('style'))) bad.push(`a colour in an inline style on <${el.tagName.toLowerCase()}>`)
          if (/\[(#|rgb|hsl|oklch)/.test(el.getAttribute('class') ?? '')) bad.push(`an arbitrary colour class on <${el.tagName.toLowerCase()}>`)
        }
        return bad
      }, [R.card, controls])
      rule(`${screenId}/${action.tool.name}: built from ${binding.system.split(' (')[0]} only`, !rogue.length, rogue.join('; ') || 'yes')
    }
  }
  for (const [id, s] of Object.entries(contract.screens)) rule(`${id}: reachable from / by links`, reached.has(s.path), s.path)

  await go('/')
  const primary = (await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--primary'))).trim()
  const want = `oklch(${tokens.color.primary.$value.components.join(' ')})`
  rule('the theme comes from tokens.json', primary === want, `--primary is "${primary}"; tokens.json says ${want}`)

  // The forms and the tools make the contract's calls; outcomes and refusals are alerts.
  const calls = []
  page.on('response', (r) => { const u = new URL(r.url()); if (u.pathname.startsWith('/api/')) calls.push({ call: `${r.request().method()} ${u.pathname}`, status: r.status() }) })
  const made = (action, args) => { const want = `${action.call.method} ${fill(action.call.path, args).split('?')[0]}`
    return calls.find((c) => c.call === want) }
  const room = `pukuhuone ${Date.now() % 100000}`
  const A = contract.actions
  await go(contract.screens.Manage.path)
  const cfg = page.locator('form[toolname="configureDays"]').first()
  await cfg.getByLabel(A.ConfigureDays.input.dressingRoom.label, { exact: true }).fill(room)
  await cfg.getByLabel(A.ConfigureDays.input.sauna.label, { exact: true }).fill('F')
  await cfg.getByRole('group', { name: A.ConfigureDays.input.weekdays.label }).getByRole('checkbox', { name: /^tuesday$/i }).check()
  await cfg.getByLabel(A.ConfigureDays.input.slots.label, { exact: true }).fill('17:00, 18:30')
  await cfg.getByRole('button', { name: A.ConfigureDays.submit, exact: true }).click()
  const saved = (await scopeOf('configureDays')).getByRole('alert')
  const savedShown = await saved.first().waitFor({ timeout: 5000 }).then(() => true, () => false)
  const c1 = made(A.ConfigureDays, { dressingRoom: room })
  rule("a person's Save makes ConfigureDays' call", c1 && c1.status < 300, c1 ? `${c1.call} ${c1.status}` : calls.map((c) => c.call).join(', ') || 'no call')
  rule('the outcome is shown as an alert in the card', savedShown, savedShown ? await saved.first().innerText() : 'no alert')

  await go(contract.screens.Home.path)
  const viaTool = await page.evaluate(async (room) => {
    const t = window.__tools.find((t) => t.name === 'requestStandingSlot'); if (!t) return 'no tool'
    try { await t.execute({ household: '12', dressingRoom: room, weekday: 'tuesday', start: '17:00' }); return 'called' } catch (e) { return String(e) }
  }, room)
  const c2 = made(A.RequestStandingSlot, {})
  rule("an agent's requestStandingSlot tool makes its call", viaTool === 'called' && c2 && c2.status < 300, `${viaTool}; ${c2 ? `${c2.call} ${c2.status}` : 'no call'}`)

  const req = page.locator('form[toolname="requestStandingSlot"]').first()
  const I = A.RequestStandingSlot.input
  await req.getByLabel(I.household.label, { exact: true }).fill('3')
  await req.getByLabel(I.dressingRoom.label, { exact: true }).fill(room)
  await req.getByLabel(I.weekday.label, { exact: true }).selectOption('tuesday')
  await req.getByLabel(I.start.label, { exact: true }).fill('17:00')
  await req.getByRole('button', { name: A.RequestStandingSlot.submit, exact: true }).click()
  const scope = await scopeOf('requestStandingSlot')
  const refusal = scope.getByRole('alert').filter({ hasText: 'held by another household' })
  const refused = await refusal.first().waitFor({ timeout: 5000 }).then(() => true, () => false)
  rule('a refusal is shown with its must text', refused, refused ? await refusal.first().innerText() : 'no alert saying "held by another household"')
  const offer = refusal.first().getByRole('button', { name: /18:30/ }).or(refusal.first().getByRole('link', { name: /18:30/ }))
  const offered = refused && (await offer.count()) > 0
  rule('the refusal offers the nearest free slot', offered, offered ? await offer.first().innerText() : 'no button or link naming 18:30')
  if (offered) {
    await offer.first().click()
    const ok = scope.getByRole('alert').filter({ hasText: /Confirmed: apartment 3 /i })
    rule('taking the offered slot confirms it', await ok.first().waitFor({ timeout: 5000 }).then(() => true, () => false), 'no "Confirmed: apartment 3 …" alert')
  } else rule('taking the offered slot confirms it', false, 'nothing offered')
  await browser.close()
}

function report() {
  const failed = results.filter((r) => !r.ok)
  for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok ? '' : `  (${r.detail})`}`)
  console.log(`${results.length - failed.length} of ${results.length} conformance rules hold`)
  return failed.length
}
main().then(() => process.exit(report() ? 1 : 0),
  (e) => { report(); console.log(`cannot run the conformance check, after the rules above: ${e.message.split('\n')[0]}`); process.exit(2) })
