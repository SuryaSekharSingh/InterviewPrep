'use strict';
let token = null;
let bank = [];
const byId = id => document.getElementById(id);
const status = text => { byId('status').textContent = text; };
async function api(path, method = 'GET', body) {
  const response = await fetch('/api/v1/' + path, {
    method, cache: 'no-store',
    headers: {'Content-Type': 'application/json', ...(token ? {Authorization: 'Bearer ' + token} : {})},
    ...(body === undefined ? {} : {body: JSON.stringify(body)})
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.message || 'Request failed (' + response.status + ').');
  return data;
}
async function run(action) {
  const buttons = [...document.querySelectorAll('button')];
  buttons.forEach(button => button.disabled = true);
  status('Working…');
  try { await action(); status('Ready.'); }
  catch (error) { status(error.message); }
  finally { buttons.forEach(button => button.disabled = false); }
}
function element(tag, text, parent) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (parent) parent.appendChild(node);
  return node;
}
function action(parent, label, callback) {
  const button = element('button', label, parent);
  button.addEventListener('click', () => run(callback));
}
function resetList(title) {
  const list = byId('list'); list.replaceChildren(); element('h2', title, list); return list;
}
async function questions() {
  bank = await api('admin/questions');
  const list = resetList('Question bank');
  if (!bank.length) element('p', 'No questions yet. Import reviewed-source drafts to begin.', list);
  for (const question of bank) {
    const card = element('article', undefined, list);
    element('small', question.topicId + ' · ' + question.difficulty + ' · ' + question.type + ' · ' + question.state + ' · v' + question.version, card);
    element('h3', question.prompt, card);
    element('pre', JSON.stringify({options: question.options, answer: question.answer, explanation: question.explanation, criteria: question.criteria, source: question.source, license: question.license, author: question.author, reviewer: question.reviewer}, null, 2), card);
    if (question.state === 'DRAFT') action(card, 'Review and publish', async () => {
      if (!confirm('I independently checked the answer, explanation, source/licence and topic mapping. Publish this version?')) return;
      await api('admin/questions/' + question.id + '/publish', 'POST', {}); await questions();
    });
    if (question.state === 'PUBLISHED') action(card, 'Retire', async () => {
      await api('admin/questions/' + question.id + '/retire', 'POST', {}); await questions();
    });
    action(card, 'Prepare revision', async () => {
      byId('draft').value = JSON.stringify({...question, id: null, reviewer: null, state: 'DRAFT'}, null, 2);
      byId('draft').closest('details').open = true; byId('draft').focus();
    });
  }
}
async function seeds() {
  const prompts = await api('admin/seeds'); const list = resetList('Interview prompts');
  if (!prompts.length) element('p', 'The interview prompt pack has not been prepared yet.', list);
  for (const prompt of prompts) {
    const card = element('article', undefined, list);
    element('small', prompt.roleId + ' · ' + prompt.kind + ' · ' + prompt.topic + ' · ' + prompt.state, card);
    element('h3', prompt.prompt, card); element('p', prompt.referenceAnswer, card);
    if (prompt.state === 'DRAFT') action(card, 'Review and publish', async () => {
      if (!confirm('I checked the prompt and reference answer. Publish?')) return;
      await api('admin/seeds/' + prompt.id + '/publish', 'POST', {}); await seeds();
    });
  }
}
async function reviews() {
  const items = await api('admin/reviews'); const list = resetList('Pending short-answer marks');
  if (!items.length) element('p', 'No responses are waiting for review.', list);
  for (const item of items) {
    const card = element('article', undefined, list);
    element('h3', item.prompt, card); element('p', 'Response: ' + item.response, card);
    element('p', 'Reference: ' + item.answer, card); element('pre', item.criteria, card);
    const scoreLabel = element('label', 'Score out of 100', card);
    const score = element('input', undefined, scoreLabel); score.type = 'number'; score.min = '0'; score.max = '100'; score.step = '0.1';
    const reasonLabel = element('label', 'Review reason', card); const reason = element('textarea', undefined, reasonLabel);
    action(card, 'Save review', async () => {
      if (!score.value || !reason.value.trim()) throw new Error('Enter a score and review reason.');
      await api('admin/reviews/' + item.activity_id + '/' + item.item_id, 'POST', {points: Number(score.value), reason: reason.value.trim()}); await reviews();
    });
  }
}
byId('signin').addEventListener('submit', event => {
  event.preventDefault();
  run(async () => {
    const session = await api('auth/login', 'POST', {username: byId('username').value.trim(), password: byId('password').value});
    byId('password').value = ''; token = session.token;
    await questions();
    byId('workspace').hidden = false; byId('login').hidden = true;
  });
});
byId('questions').onclick = () => run(questions);
byId('seeds').onclick = () => run(seeds);
byId('reviews').onclick = () => run(reviews);
byId('logout').onclick = () => run(async () => {
  try { await api('auth/logout', 'POST', {}); }
  finally { token = null; bank = []; byId('list').replaceChildren(); byId('workspace').hidden = true; byId('login').hidden = false; }
});
byId('save').onclick = () => run(async () => { await api('admin/questions', 'POST', JSON.parse(byId('draft').value)); byId('draft').value = ''; await questions(); });
byId('import').onchange = () => run(async () => {
  const file = byId('import').files[0]; if (!file) return;
  if (file.size > 2000000) throw new Error('Keep imports under 2 MB.');
  const data = JSON.parse(await file.text());
  if (!Array.isArray(data)) throw new Error('Import a JSON array.');
  await api('admin/questions/import', 'POST', data); byId('import').value = ''; await questions();
});
byId('export').onclick = () => run(async () => {
  const all = await api('admin/questions'); const url = URL.createObjectURL(new Blob([JSON.stringify(all, null, 2)], {type: 'application/json'}));
  const link = element('a'); link.href = url; link.download = 'interviewedge-questions.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
});
