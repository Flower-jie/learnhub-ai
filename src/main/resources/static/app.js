'use strict';

const $ = id => document.getElementById(id);
const state = { token: '', config: null, kbs: [], kbId: null, view: 'library', sessions: [], sessionId: null, notes: [], noteId: null, busy: false, noteDirty: false };

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function button(label, className, action) {
  const node = element('button', className, label);
  node.type = 'button';
  node.addEventListener('click', () => perform(action));
  return node;
}

function notify(message, error = false) {
  $('notice').textContent = message;
  $('notice').classList.toggle('error', error);
  $('notice').hidden = false;
}

async function perform(action) {
  if (state.busy) return;
  state.busy = true;
  document.body.setAttribute('aria-busy', 'true');
  $('progress').hidden = false;
  const enabled = [...document.querySelectorAll('button:not(:disabled)')];
  enabled.forEach(node => { node.disabled = true; });
  try {
    await action();
  } catch (error) {
    const message = error instanceof TypeError ? '无法连接本地服务，请确认服务仍在运行。' : error.message;
    if ($('authDialog').open) $('authError').textContent = message;
    else if ($('kbDialog').open) $('kbError').textContent = message;
    else notify(message, true);
  } finally {
    enabled.forEach(node => { node.disabled = false; });
    state.busy = false;
    document.body.setAttribute('aria-busy', 'false');
    $('progress').hidden = true;
  }
}

async function api(path, options = {}) {
  const headers = { Authorization: `Bearer ${state.token}`, ...options.headers };
  if (options.body !== undefined && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    options = { ...options, body: JSON.stringify(options.body) };
  }
  const response = await fetch(`/api${path}`, { ...options, headers, credentials: 'same-origin' });
  if (response.status === 401) {
    lockWorkspace();
    throw new Error('访问令牌无效或已变更，请重新连接。');
  }
  if (response.status === 204) return null;
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.message || `请求失败（${response.status}）`);
  if (data === null) throw new Error('服务返回了无法读取的数据。');
  return data;
}

function dateLabel(value) {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString('zh-CN');
}

function requireKb() {
  if (!state.kbId) throw new Error('请先新建或选择一个知识库。');
  return state.kbId;
}

function mayLeaveNote() {
  return !state.noteDirty || window.confirm('当前笔记有未保存的修改，确定放弃这些修改吗？');
}

function clearNote() {
  state.noteId = null;
  state.noteDirty = false;
  $('noteForm').reset();
  $('noteState').textContent = '尚未保存';
  $('deleteNote').hidden = true;
}

function lockWorkspace() {
  state.token = '';
  state.config = null;
  state.kbs = [];
  state.kbId = null;
  state.sessions = [];
  state.sessionId = null;
  state.notes = [];
  clearNote();
  ['kbList', 'documentList', 'sessionList', 'messages', 'noteList'].forEach(id => $(id).replaceChildren());
  $('currentKb').textContent = '尚未选择知识库';
  ['kbCount', 'docCount', 'chunkCount'].forEach(id => { $(id).textContent = '0'; });
  $('modeBadge').textContent = '等待连接';
  $('accessToken').value = '';
  $('authError').textContent = '';
  $('questionForm').reset();
  $('uploadForm').reset();
  $('sessionTitle').textContent = '开始一次探索';
  $('deleteSession').hidden = true;
  $('notice').hidden = true;
  if ($('kbDialog').open) $('kbDialog').close();
  if (!$('authDialog').open) $('authDialog').showModal();
}

function renderKbs() {
  $('kbList').replaceChildren();
  for (const kb of state.kbs) {
    const node = button(kb.name, `kb-button${kb.id === state.kbId ? ' active' : ''}`, async () => {
      if (kb.id === state.kbId || !mayLeaveNote()) return;
      state.kbId = kb.id;
      state.sessionId = null;
      clearNote();
      $('question').value = '';
      renderKbs();
      await loadView();
    });
    node.setAttribute('aria-pressed', String(kb.id === state.kbId));
    $('kbList').append(node);
  }
  if (!state.kbs.length) $('kbList').append(element('p', 'muted small', '还没有知识库，点击 + 开始。'));
  const current = state.kbs.find(kb => kb.id === state.kbId);
  $('currentKb').textContent = current?.name || '尚未选择知识库';
  $('kbCount').textContent = state.kbs.length;
  $('docCount').textContent = current?.documentCount || 0;
  $('chunkCount').textContent = current?.chunkCount || 0;
  $('deleteKb').hidden = !current;
}

async function refreshKbs() {
  state.kbs = await api('/kbs');
  if (!state.kbs.some(kb => kb.id === state.kbId)) {
    state.kbId = state.kbs[0]?.id || null;
    state.sessionId = null;
    clearNote();
  }
  renderKbs();
}

async function loadDocuments() {
  const docs = state.kbId ? await api(`/kbs/${state.kbId}/documents`) : [];
  $('documentList').replaceChildren();
  if (!docs.length) $('documentList').append(element('p', 'empty', state.kbId ? '资料架还是空的。上传文件，或导入上方的入门示例。' : '新建一个知识库，从第一份学习资料开始。'));
  for (const doc of docs) {
    const row = element('div', 'document-row');
    row.append(element('span', 'document-icon', doc.name.split('.').pop().toUpperCase().slice(0, 4)));
    const info = element('div', 'document-info');
    info.append(element('strong', '', doc.name), element('p', '', `${doc.chunkCount} 个片段 · ${dateLabel(doc.createdAt)}`));
    const remove = button('删除', 'text-button danger', async () => {
      if (!window.confirm(`删除「${doc.name}」及其索引？历史引用将标记为来源已删除。`)) return;
      await api(`/documents/${doc.id}`, { method: 'DELETE' });
      await refreshKbs();
      await loadDocuments();
      notify('资料与索引已删除。');
    });
    remove.setAttribute('aria-label', `删除资料 ${doc.name}`);
    row.append(info, remove);
    $('documentList').append(row);
  }
}

async function changeView(view) {
  if (state.view !== view && !mayLeaveNote()) return;
  if (state.view !== view && state.noteDirty) clearNote();
  state.view = view;
  ['library', 'chat', 'notes'].forEach(name => { $(`${name}View`).hidden = name !== view; });
  document.querySelectorAll('[data-view]').forEach(node => {
    if (node.dataset.view === view) node.setAttribute('aria-current', 'page');
    else node.removeAttribute('aria-current');
  });
  $('notice').hidden = true;
  await loadView();
}

async function loadView() {
  if (state.view === 'library') await loadDocuments();
  if (state.view === 'chat') { await loadSessions(); await loadMessages(); }
  if (state.view === 'notes') await loadNotes();
}

function showKbDialog() {
  if (!mayLeaveNote()) return;
  $('kbName').value = '';
  $('kbError').textContent = '';
  $('kbDialog').showModal();
  $('kbName').focus();
}

async function importFile(file) {
  const kbId = requireKb();
  if (!file || !/\.(txt|md|pdf)$/i.test(file.name)) throw new Error('请选择 TXT、Markdown 或 PDF 文件。');
  if (file.size > 10 * 1024 * 1024) throw new Error('文件超过 10 MB，请拆分后导入。');
  if (!file.size) throw new Error('不能导入空文件。');
  const body = new FormData();
  body.append('file', file);
  const doc = await api(`/kbs/${kbId}/documents`, { method: 'POST', body });
  await refreshKbs();
  await loadDocuments();
  $('uploadForm').reset();
  notify(`「${doc.name}」已建立索引，共 ${doc.chunkCount} 个片段。可以切换到学习问答进行检索。`);
}

async function loadSessions() {
  state.sessions = state.kbId ? await api(`/sessions?kbId=${state.kbId}`) : [];
  if (!state.sessions.some(session => session.id === state.sessionId)) state.sessionId = null;
  renderSessions();
}

function renderSessions() {
  $('sessionList').replaceChildren();
  if (!state.sessions.length) $('sessionList').append(element('p', 'empty', '发送问题后，会话会自动保存。'));
  for (const session of state.sessions) {
    const node = button(session.title, `list-button${session.id === state.sessionId ? ' active' : ''}`, async () => {
      state.sessionId = session.id;
      renderSessions();
      await loadMessages();
    });
    node.append(element('small', '', dateLabel(session.createdAt)));
    $('sessionList').append(node);
  }
  $('sessionTitle').textContent = state.sessions.find(session => session.id === state.sessionId)?.title || '开始一次探索';
  $('deleteSession').hidden = !state.sessionId;
}

function renderMessage(message) {
  const wrapper = element('article', `message ${message.role}`);
  const heading = element('div', 'message-heading');
  heading.append(element('span', '', message.role === 'user' ? '你 / QUESTION' : '知行 / EVIDENCE'));
  if (message.role === 'assistant' && (message.content || message.citations?.length)) {
    heading.append(button('整理成笔记 ↗', 'text-button', async () => {
      if (!mayLeaveNote()) return;
      const parts = [];
      if (message.content) parts.push(message.content);
      for (const [index, citation] of (message.citations || []).entries()) {
        parts.push(`[${index + 1}] ${citation.documentName}${citation.page ? ` · 第 ${citation.page} 页` : ''}\n${citation.sourceAvailable === false ? '来源已删除' : citation.text}`);
      }
      clearNote();
      await changeView('notes');
      $('noteTitle').value = (state.sessions.find(session => session.id === state.sessionId)?.title || '检索学习笔记').slice(0, 100);
      $('noteBody').value = parts.join('\n\n').slice(0, 20000);
      state.noteDirty = true;
      $('noteState').textContent = '未保存的修改';
      $('noteTitle').focus();
    }));
  }
  wrapper.append(heading);
  if (message.content) wrapper.append(element('div', 'message-content', message.content));
  else wrapper.append(element('div', 'retrieval-label', message.citations?.length ? '以下是检索到的资料片段，不是模型生成的回答。请结合原文判断相关性。' : '没有找到足够相关的资料。请调整提问，或先导入相关内容；不会在缺少依据时生成答案。'));
  if (message.citations?.length) {
    const citations = element('div', 'citations');
    message.citations.forEach((citation, index) => {
      const card = element('details', 'citation');
      const location = citation.page ? `第 ${citation.page} 页` : `片段 ${citation.ordinal}`;
      const status = citation.sourceAvailable === false ? ' · 来源已删除' : ` · 相似度 ${Number(citation.score).toFixed(2)}`;
      card.append(element('summary', '', `[${index + 1}] ${citation.documentName} · ${location}${status}`));
      card.append(element('p', '', citation.sourceAvailable === false ? '该资料已从知识库删除，不能再作为当前问答的证据。' : citation.text));
      citations.append(card);
    });
    wrapper.append(citations);
  }
  $('messages').append(wrapper);
}

async function loadMessages() {
  $('messages').replaceChildren();
  if (!state.sessionId) {
    const welcome = element('div', 'chat-welcome');
    welcome.append(element('span', 'big-mark', '“'), element('h3', '', '从一个好问题开始。'), element('p', 'muted', '例如：RAG 和模型微调有什么区别？\n每次问答只检索当前知识库，引用可以展开查看原文。'));
    $('messages').append(welcome);
    return;
  }
  const messages = await api(`/sessions/${state.sessionId}/messages`);
  messages.forEach(renderMessage);
  if (!messages.length) $('messages').append(element('p', 'empty', '这个会话还没有消息。'));
  $('messages').scrollTop = $('messages').scrollHeight;
}

async function sendQuestion() {
  const kbId = requireKb();
  const question = $('question').value.trim();
  if (!question) throw new Error('请输入问题。');
  if ($('searchOnly').checked) {
    const result = await api(`/kbs/${kbId}/search`, { method: 'POST', body: { query: question, topK: 5 } });
    state.sessionId = null;
    renderSessions();
    $('sessionTitle').textContent = '原文检索 · 不保存为会话';
    $('messages').replaceChildren();
    renderMessage({ role: 'user', content: question });
    renderMessage({ role: 'assistant', content: null, mode: 'retrieval_only', citations: result.hits });
  } else {
    if (!state.sessionId) {
      const session = await api('/sessions', { method: 'POST', body: { kbId, title: question.slice(0, 100) } });
      state.sessionId = session.id;
      await loadSessions();
    }
    await api(`/sessions/${state.sessionId}/messages`, { method: 'POST', body: { question } });
    await loadMessages();
  }
  $('question').value = '';
  $('messages').scrollTop = $('messages').scrollHeight;
}

async function loadNotes() {
  state.notes = state.kbId ? await api(`/notes?kbId=${state.kbId}`) : [];
  renderNotes();
}

function renderNotes() {
  $('noteList').replaceChildren();
  if (!state.notes.length) $('noteList').append(element('p', 'empty', '记录第一条学习收获。'));
  for (const note of state.notes) {
    const node = button(note.title, `list-button${note.id === state.noteId ? ' active' : ''}`, async () => {
      if (!mayLeaveNote()) return;
      state.noteId = note.id;
      state.noteDirty = false;
      $('noteTitle').value = note.title;
      $('noteBody').value = note.body;
      $('noteState').textContent = '已保存到本地';
      $('deleteNote').hidden = false;
      renderNotes();
    });
    node.append(element('small', '', dateLabel(note.createdAt)));
    $('noteList').append(node);
  }
}

$('authDialog').addEventListener('cancel', event => event.preventDefault());
$('authForm').addEventListener('submit', event => {
  event.preventDefault();
  perform(async () => {
    state.token = $('accessToken').value.trim();
    $('authError').textContent = '';
    state.config = await api('/config');
    await refreshKbs();
    await loadView();
    $('modeBadge').textContent = state.config.retrievalMode === 'embedding' ? '语义向量检索' : '本地词项哈希检索';
    $('chatModeHelp').textContent = state.config.chatEnabled ? '已启用生成模型，回答附带知识库证据。模型可能出错，请核对引用。' : '未配置生成模型：问答返回原文检索结果，不会模拟 AI 回答。';
    $('accessToken').value = '';
    $('authDialog').close();
  });
});
['disconnect', 'lockTop'].forEach(id => $(id).addEventListener('click', () => { if (!state.busy && mayLeaveNote()) lockWorkspace(); }));
document.querySelectorAll('[data-view]').forEach(node => node.addEventListener('click', () => perform(() => changeView(node.dataset.view))));
['addKb', 'createKbHero'].forEach(id => $(id).addEventListener('click', () => { if (!state.busy) showKbDialog(); }));
$('closeKbDialog').addEventListener('click', () => { if (!state.busy) $('kbDialog').close(); });
$('kbDialog').addEventListener('cancel', event => { if (state.busy) event.preventDefault(); });
$('kbForm').addEventListener('submit', event => {
  event.preventDefault();
  perform(async () => {
    const name = $('kbName').value.trim();
    if (!name) throw new Error('请输入知识库名称。');
    const kb = await api('/kbs', { method: 'POST', body: { name } });
    state.kbId = kb.id;
    state.sessionId = null;
    clearNote();
    await refreshKbs();
    await loadView();
    $('kbDialog').close();
    notify('知识库已创建，可以开始添加资料。');
  });
});
$('deleteKb').addEventListener('click', () => perform(async () => {
  const id = requireKb();
  if (!mayLeaveNote() || !window.confirm('删除当前知识库及全部资料、会话和笔记？此操作无法撤销。')) return;
  await api(`/kbs/${id}`, { method: 'DELETE' });
  await refreshKbs();
  await loadView();
  notify('知识库及关联数据已删除。');
}));
$('uploadForm').addEventListener('submit', event => { event.preventDefault(); perform(() => importFile($('documentFile').files[0])); });
$('sampleImport').addEventListener('click', () => perform(async () => {
  if (!state.kbId) {
    const kb = await api('/kbs', { method: 'POST', body: { name: 'RAG 入门' } });
    state.kbId = kb.id;
    await refreshKbs();
  }
  const response = await fetch('sample-knowledge.txt');
  if (!response.ok) throw new Error('无法读取示例资料。');
  await importFile(new File([await response.blob()], 'RAG入门示例.txt', { type: 'text/plain' }));
}));
$('newSession').addEventListener('click', () => perform(async () => { requireKb(); state.sessionId = null; renderSessions(); await loadMessages(); $('question').focus(); }));
$('deleteSession').addEventListener('click', () => perform(async () => {
  if (!state.sessionId || !window.confirm('删除当前会话和全部消息？')) return;
  await api(`/sessions/${state.sessionId}`, { method: 'DELETE' });
  state.sessionId = null;
  await loadSessions();
  await loadMessages();
  notify('会话已删除。');
}));
$('questionForm').addEventListener('submit', event => { event.preventDefault(); perform(sendQuestion); });
$('question').addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    if (!state.busy) $('questionForm').requestSubmit();
  }
});
$('newNote').addEventListener('click', () => perform(async () => { requireKb(); if (!mayLeaveNote()) return; clearNote(); renderNotes(); $('noteTitle').focus(); }));
['noteTitle', 'noteBody'].forEach(id => $(id).addEventListener('input', () => { state.noteDirty = true; $('noteState').textContent = '未保存的修改'; }));
$('noteForm').addEventListener('submit', event => {
  event.preventDefault();
  perform(async () => {
    const kbId = requireKb();
    const title = $('noteTitle').value.trim();
    const body = $('noteBody').value.trim();
    if (!title || !body) throw new Error('请填写笔记标题和正文。');
    const saved = state.noteId ? await api(`/notes/${state.noteId}`, { method: 'PUT', body: { title, body } }) : await api('/notes', { method: 'POST', body: { kbId, title, body } });
    state.noteId = saved.id;
    state.noteDirty = false;
    $('noteState').textContent = '已保存到本地';
    $('deleteNote').hidden = false;
    await loadNotes();
    notify('笔记已保存。');
  });
});
$('deleteNote').addEventListener('click', () => perform(async () => {
  if (!state.noteId || !window.confirm('删除这条笔记？')) return;
  await api(`/notes/${state.noteId}`, { method: 'DELETE' });
  clearNote();
  await loadNotes();
  notify('笔记已删除。');
}));
window.addEventListener('beforeunload', event => { if (state.noteDirty) { event.preventDefault(); event.returnValue = ''; } });

function applyTheme(dark) {
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  $('themeToggle').setAttribute('aria-label', dark ? '切换浅色模式' : '切换深色模式');
  $('themeToggle').setAttribute('aria-pressed', String(dark));
}
let theme;
try { theme = localStorage.getItem('learnhub-theme'); } catch (_) {}
applyTheme(theme ? theme === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches);
$('themeToggle').addEventListener('click', () => {
  const dark = document.documentElement.dataset.theme !== 'dark';
  applyTheme(dark);
  try { localStorage.setItem('learnhub-theme', dark ? 'dark' : 'light'); } catch (_) {}
});
lockWorkspace();
