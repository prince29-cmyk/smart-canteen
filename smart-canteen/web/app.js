// ===================== state + helpers =====================
const TOKEN_KEY = 'canteen_student_token';

const state = {
  user: null, token: null,                       // student
  staff: false, staffInfo: null, staffToken: null, // staff
  menu: [], addons: [],
  cart: {}, extras: [], note: '',
  confirm: null, tab: null,
  auditDay: null, rateDraft: {},
};

const $ = (s) => document.querySelector(s);
const money = (n) => '₹' + Number(n).toFixed(2);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const stars = (n) => '★'.repeat(n) + '☆'.repeat(5 - n);

// the login token is saved on the student's device (localStorage can be blocked, so always try/catch)
const store = {
  get() { try { return localStorage.getItem(TOKEN_KEY); } catch (e) { return null; } },
  set(v) { try { localStorage.setItem(TOKEN_KEY, v); } catch (e) { /* ignore */ } },
  clear() { try { localStorage.removeItem(TOKEN_KEY); } catch (e) { /* ignore */ } },
};

function toast(msg, bad = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show' + (bad ? ' bad' : '');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.className = 'toast'), 4200);
}

async function api(path, method = 'GET', data) {
  const opt = { method, headers: {} };
  if (state.token) opt.headers['X-Student-Token'] = state.token;
  if (state.staffToken) opt.headers['X-Staff-Token'] = state.staffToken;
  if (data) {
    opt.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    opt.body = new URLSearchParams(data).toString();
  }
  const res = await fetch('/api' + path, opt);
  let json = {};
  try { json = await res.json(); } catch (e) { /* ignore */ }
  if (res.status === 401) {                       // login missing or expired
    sessionExpired();
    throw new Error(json.error || 'Please log in again');
  }
  if (!res.ok) throw new Error(json.error || 'Request failed');
  return json;
}

function show(view) {
  for (const v of ['auth', 'stafflogin', 'student', 'staff']) {
    $('#view-' + v).classList.toggle('hidden', v !== view);
  }
  $('#userbar').classList.toggle('hidden', view === 'auth' || view === 'stafflogin');
}

function updateBar() {
  if (state.staff) {
    $('#who').textContent = state.staffInfo
      ? state.staffInfo.name + ' (' + state.staffInfo.staff_code + ', ' + state.staffInfo.role + ')'
      : 'Canteen staff';
    $('#wallet').classList.add('hidden');
  } else if (state.user) {
    $('#who').textContent = state.user.name + (state.user.college_name ? ' · ' + state.user.college_name : '');
    $('#wallet').classList.remove('hidden');
    $('#wallet').textContent = '💳 ' + money(state.user.wallet_balance);
    $('#wallet-big').textContent = money(state.user.wallet_balance);
  }
}

function resetState(showToast) {
  const was = state.token || state.staffToken;
  state.user = null; state.token = null;
  state.staff = false; state.staffInfo = null; state.staffToken = null;
  state.cart = {}; state.extras = []; state.note = ''; state.confirm = null;
  state.tab = null; state.auditDay = null; state.rateDraft = {};
  store.clear();
  show('auth');
  if (showToast && was) toast('Your login expired. Please log in again.', true);
}

function sessionExpired() { resetState(true); }

function logout() {
  if (state.staffToken) api('/staff/logout', 'POST').catch(() => {});
  else if (state.token) api('/student/logout', 'POST').catch(() => {});
  resetState(false);
}

// ===================== tabs =====================
const loaders = {
  order: async () => { await Promise.all([loadMenu(), loadAddons()]); renderMenu(); renderCart(); },
  favs: async () => { await loadMenu(); renderFavs(); },
  myorders: loadMyOrders,
  wallettab: loadWallet,
  sOrders: loadStaffOrders,
  sInventory: loadInventory,
  sAddons: loadAddonsAdmin,
  sAudit: loadAudit,
  sFeedback: loadFeedback,
};

function openTab(group, id) {
  const nav = document.querySelector(`.tabs[data-group="${group}"]`);
  nav.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === id));
  nav.parentElement.querySelectorAll('.panel').forEach((p) => p.classList.toggle('hidden', p.id !== 'tab-' + id));
  state.tab = id;
  loaders[id]().catch((e) => toast(e.message, true));
}

document.querySelectorAll('.tabs').forEach((nav) => {
  nav.addEventListener('click', (e) => {
    const b = e.target.closest('.tab');
    if (b) openTab(nav.dataset.group, b.dataset.tab);
  });
});

// ===================== student login / register =====================
function setAuthMode(mode) {
  document.querySelectorAll('.auth-tab').forEach((b) => b.classList.toggle('active', b.dataset.mode === mode));
  $('#login-form').classList.toggle('hidden', mode !== 'login');
  $('#register-form').classList.toggle('hidden', mode !== 'register');
}

function startStudentSession(r) {
  state.token = r.token;
  store.set(r.token);                              // keep the student logged in on this device
  state.user = r;
  state.staff = false;
  state.cart = {}; state.extras = []; state.note = ''; state.confirm = null;
  show('student');
  updateBar();
  openTab('student', 'order');
}

$('#login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    startStudentSession(await api('/student/login', 'POST', {
      mobile: $('#login-mobile').value,
      password: $('#login-pass').value,
    }));
    $('#login-pass').value = '';
  } catch (err) { toast(err.message, true); }
});

$('#register-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  if ($('#reg-pass').value !== $('#reg-pass2').value) { toast('Passwords do not match', true); return; }
  try {
    startStudentSession(await api('/student/register', 'POST', {
      name: $('#reg-name').value,
      mobile: $('#reg-mobile').value,
      college: $('#reg-college').value,
      dept_id: $('#reg-dept').value,
      password: $('#reg-pass').value,
    }));
    $('#reg-pass').value = ''; $('#reg-pass2').value = '';
    toast('Welcome, ' + state.user.name + '! Your account is ready.');
  } catch (err) { toast(err.message, true); }
});

async function loadAuthLists() {
  try {
    const [colleges, depts] = await Promise.all([api('/colleges'), api('/departments')]);
    $('#college-list').innerHTML = colleges.map((c) => `<option value="${esc(c.college_name)}"></option>`).join('');
    $('#reg-dept').innerHTML = '<option value="">Department (optional)</option>'
      + depts.map((d) => `<option value="${d.dept_id}">${esc(d.dept_name)}</option>`).join('');
  } catch (e) { /* the lists are optional */ }
}

// ===================== student: menu, favourites, cart =====================
async function loadMenu() { state.menu = await api('/menu'); }
async function loadAddons() { state.addons = await api('/customizations'); }

function itemCard(m) {
  const avail = m.is_available && m.stock > 0;
  const cls = !avail ? 'none' : m.stock <= 5 ? 'low' : '';
  const label = !avail ? 'Sold out' : m.stock <= 5 ? `Only ${m.stock} left` : `${m.stock} in stock`;
  const fav = m.is_favourite == 1;
  return `
    <div class="item ${avail ? '' : 'out'}">
      <button class="heart ${fav ? 'on' : ''}" data-act="fav" data-id="${m.item_id}" title="${fav ? 'Remove from favourites' : 'Add to favourites'}">${fav ? '♥' : '♡'}</button>
      <h4>${esc(m.item_name)}</h4>
      <span class="price">${money(m.price)}</span>
      <span class="stock ${cls}">${label}</span>
      <button class="btn primary small" data-act="add" data-id="${m.item_id}" ${avail ? '' : 'disabled'}>Add to cart</button>
    </div>`;
}

function renderMenu() {
  const groups = {};
  state.menu.forEach((m) => (groups[m.category_name] ||= []).push(m));
  $('#menu').innerHTML = Object.entries(groups).map(([cat, items]) => `
    <h3 class="category">${esc(cat)}</h3>
    <div class="grid">${items.map(itemCard).join('')}</div>`).join('');
}

function renderFavs() {
  const favs = state.menu.filter((m) => m.is_favourite == 1);
  $('#favs').innerHTML = favs.length
    ? `<div class="grid">${favs.map(itemCard).join('')}</div>`
    : '<p class="empty">No favourites yet. Tap the ♡ on any item to save it here.</p>';
}

function cartLines() {
  return Object.entries(state.cart).map(([id, qty]) => {
    const m = state.menu.find((x) => x.item_id == id);
    return m ? { m, qty } : null;
  }).filter(Boolean);
}

function cartTotals() {
  const lines = cartLines();
  const items = lines.reduce((s, l) => s + l.qty * Number(l.m.price), 0);
  const extras = state.addons
    .filter((a) => state.extras.includes(a.custom_id))
    .reduce((s, a) => s + Number(a.extra_price), 0);
  return { lines, items, extras, total: items + extras };
}

function renderCart() {
  const box = $('#cart');
  const { lines, items, extras, total } = cartTotals();

  if (!lines.length) {
    const c = state.confirm;
    box.innerHTML = (c ? `
      <div class="confirm">
        <b>✅ Token #${c.daily_no} placed!</b>
        <div class="queue-big">You are #${c.queue_position} in the queue.<br>Your order will be delivered in about ${c.eta_minutes} minutes.</div>
        <div class="muted small">Paid ${money(c.total)}. Track it in the My orders tab. Cancel within 5 minutes for a refund.</div>
      </div>` : '')
      + '<p class="muted">Your cart is empty. Add something tasty!</p>';
    return;
  }

  const balance = Number(state.user.wallet_balance);
  const short = total > balance;
  box.innerHTML = lines.map((l) => `
    <div class="cart-line">
      <span>${esc(l.m.item_name)}<br><small class="muted">${money(l.m.price)} each</small></span>
      <span class="qty">
        <button data-act="dec" data-id="${l.m.item_id}">−</button>
        <b>${l.qty}</b>
        <button data-act="inc" data-id="${l.m.item_id}">+</button>
      </span>
    </div>`).join('') + `
    ${state.addons.length ? `
    <div class="note-box">
      <label>Make it your way (extra cost set by the canteen)</label>
      <div class="chips">
        ${state.addons.map((a) => `
          <button type="button" class="addon ${state.extras.includes(a.custom_id) ? 'on' : ''}" data-act="toggleAddon" data-id="${a.custom_id}">
            ${esc(a.custom_name)} <small>${Number(a.extra_price) > 0 ? '+' + money(a.extra_price) : 'Free'}</small>
          </button>`).join('')}
      </div>
    </div>` : ''}
    <div class="note-box">
      <label for="note">📝 Anything else? (optional)</label>
      <textarea id="note" maxlength="200" rows="2" placeholder="e.g. no ice, pack separately">${esc(state.note)}</textarea>
    </div>
    <div class="cart-sub"><span>Items</span><span>${money(items)}</span></div>
    ${extras > 0 ? `<div class="cart-sub"><span>Add-ons</span><span>${money(extras)}</span></div>` : ''}
    <div class="cart-total"><span>Total</span><span>${money(total)}</span></div>
    <p class="muted small">Wallet: ${money(balance)}</p>
    ${short ? '<p class="warn">Not enough balance. Add money in the Wallet tab.</p>' : ''}
    <button class="btn primary wide" data-act="pay" ${short ? 'disabled' : ''}>Pay from wallet</button>`;
}

function changeQty(id, delta) {
  const m = state.menu.find((x) => x.item_id == id);
  const next = (state.cart[id] || 0) + delta;
  if (next <= 0) delete state.cart[id];
  else if (m && next > m.stock) { toast('Only ' + m.stock + ' available', true); return; }
  else state.cart[id] = next;
  renderCart();
}

async function pay() {
  const items = Object.entries(state.cart).map(([id, q]) => id + ':' + q).join(',');
  try {
    const r = await api('/my/order', 'POST', {
      items, extras: state.extras.join(','), note: state.note,
    });
    state.cart = {}; state.extras = []; state.note = '';
    state.confirm = r;
    state.user.wallet_balance = r.wallet_balance;
    updateBar();
    toast(`Token #${r.daily_no} placed! You are #${r.queue_position} in the queue. Delivery in about ${r.eta_minutes} minutes.`);
  } catch (e) {
    toast(e.message, true);   // the transaction was rolled back on the server
  }
  await Promise.all([loadMenu(), loadAddons()]);
  renderMenu();
  renderCart();
}

async function toggleFavourite(id) {
  const r = await api('/my/favourite', 'POST', { item_id: id });
  const m = state.menu.find((x) => x.item_id == id);
  if (m) m.is_favourite = r.favourite ? 1 : 0;
  if (state.tab === 'favs') renderFavs(); else renderMenu();
}

// ===================== student: orders =====================
function fmtExtras(s) {
  if (!s) return '';
  return s.split(';').map((x) => {
    const [name, price] = x.split('|');
    return esc(name) + (Number(price) > 0 ? ` (+${money(price)})` : '');
  }).join(', ');
}

function cancelControl(o) {
  const left = Number(o.refund_seconds_left);
  if (left > 0) {
    return `<span class="cancel-wrap" data-until="${Date.now() + left * 1000}">
      <button class="btn danger small" data-act="cancel" data-id="${o.order_id}" data-refund="1">Cancel &amp; refund</button>
      <span class="cd muted small"></span></span>`;
  }
  return `<span class="cancel-wrap">
    <button class="btn danger small" data-act="cancel" data-id="${o.order_id}" data-refund="0">Cancel (no refund)</button>
    <span class="muted small">5-minute refund time is over</span></span>`;
}

function tickCountdowns() {
  document.querySelectorAll('.cancel-wrap[data-until]').forEach((w) => {
    const left = Math.ceil((Number(w.dataset.until) - Date.now()) / 1000);
    const cd = w.querySelector('.cd');
    const btn = w.querySelector('button');
    if (left > 0) {
      cd.textContent = Math.floor(left / 60) + ':' + String(left % 60).padStart(2, '0') + ' left for refund';
    } else {
      w.removeAttribute('data-until');
      btn.dataset.refund = '0';
      btn.textContent = 'Cancel (no refund)';
      cd.textContent = '5-minute refund time is over';
    }
  });
}

function refundLine(o) {
  if (o.status !== 'Cancelled') return '';
  if (Number(o.refund_amount) > 0)
    return `<div class="queue ok">↩️ Cancelled${o.cancelled_by === 'Staff' ? ' by the canteen' : ''}. ${money(o.refund_amount)} refunded to your wallet.</div>`;
  return '<div class="queue bad">Cancelled. No refund (it was cancelled after the 5-minute refund time).</div>';
}

function rateBox(o) {
  if (o.rating != null) {
    return `<div class="rated">${stars(Number(o.rating))}${o.review_text ? ' · ' + esc(o.review_text) : ''}</div>`;
  }
  const d = state.rateDraft[o.order_id] || { n: 0, text: '' };
  return `<div class="rate">
    <div class="muted small">How was this order?</div>
    <div class="stars">${[1, 2, 3, 4, 5].map((n) =>
      `<button type="button" class="star ${n <= d.n ? 'on' : ''}" data-act="setStar" data-order="${o.order_id}" data-n="${n}">★</button>`).join('')}</div>
    <div class="row"><input type="text" class="rate-text" data-order="${o.order_id}" maxlength="300" placeholder="Feedback (optional)" value="${esc(d.text)}">
    <button class="btn dark small" data-act="rate" data-order="${o.order_id}">Submit</button></div>
  </div>`;
}

function studentOrderCard(o) {
  let queue = '';
  if (o.queue_position != null) {
    queue = `<div class="queue">⏱️ You are #${o.queue_position} in the queue. Your order will be delivered in about ${o.eta_minutes} minutes.</div>`;
  } else if (o.status === 'Ready') {
    queue = '<div class="queue ready">✅ Your order is ready. Please collect it!</div>';
  }
  const extras = fmtExtras(o.extras);
  return `
    <div class="order">
      <header><h4>Token #${o.daily_no}</h4><span class="status s-${esc(o.status)}">${esc(o.status)}</span></header>
      <div class="items">${esc(o.items)}</div>
      ${extras ? `<div class="extras">+ ${extras}</div>` : ''}
      ${o.special_note ? `<div class="note">📝 ${esc(o.special_note)}</div>` : ''}
      ${queue}${refundLine(o)}
      <div class="meta"><span>${esc(o.order_time)}</span><b>${money(o.total_amount)}</b></div>
      <div class="actions">
        ${o.status === 'Placed' ? cancelControl(o) : ''}
        <button class="btn ghost-dark small" data-act="reorder" data-id="${o.order_id}">🔁 Reorder</button>
      </div>
      ${o.status === 'Completed' ? rateBox(o) : ''}
    </div>`;
}

async function loadMyOrders() {
  const rows = await api('/my/orders');
  $('#myorders').innerHTML = rows.length
    ? rows.map(studentOrderCard).join('')
    : '<p class="empty">No orders yet.</p>';
  tickCountdowns();
}

async function cancelOrder(id, withRefund) {
  if (!withRefund && !confirm('The 5-minute refund time is over. If you cancel now you will NOT get your money back. Cancel anyway?')) return;
  try {
    const r = await api('/my/order/cancel', 'POST', { order_id: id });
    state.user = Object.assign(state.user, await api('/me'));
    updateBar();
    toast(Number(r.refund_amount) > 0
      ? 'Order cancelled. ' + money(r.refund_amount) + ' refunded to your wallet.'
      : 'Order cancelled. No refund (5-minute refund time was over).');
  } catch (e) { toast(e.message, true); }
  await loadMyOrders();
}

async function reorder(id) {
  const r = await api('/my/order-items?order_id=' + id);
  await Promise.all([loadMenu(), loadAddons()]);
  state.cart = {}; state.confirm = null;
  const skipped = [];
  r.items.forEach((it) => {
    const m = state.menu.find((x) => x.item_id == it.item_id);
    if (!m || !m.is_available || m.stock <= 0) { skipped.push(it.item_name); return; }
    state.cart[it.item_id] = Math.min(it.quantity, m.stock);
  });
  state.extras = r.extras.map((x) => x.custom_id).filter((cid) => state.addons.some((a) => a.custom_id === cid));
  state.note = r.note || '';
  openTab('student', 'order');
  toast(skipped.length ? 'Added to cart. Not available now: ' + skipped.join(', ') : 'Your previous order is in the cart. Check it and pay.', skipped.length > 0);
}

async function submitRating(orderId) {
  const d = state.rateDraft[orderId] || { n: 0, text: '' };
  if (!d.n) { toast('Tap the stars to choose a rating (1 to 5)', true); return; }
  try {
    await api('/my/feedback', 'POST', { order_id: orderId, rating: d.n, review: d.text });
    delete state.rateDraft[orderId];
    toast('Thank you for your feedback!');
  } catch (e) { toast(e.message, true); }
  await loadMyOrders();
}

// ===================== student: wallet =====================
async function loadWallet() {
  const w = await api('/my/wallet');
  state.user.wallet_balance = w.balance;
  updateBar();
  const label = { TOPUP: 'Top-up', PAYMENT: 'Payment', REFUND: 'Refund' };
  $('#statement').innerHTML = w.transactions.length ? `
    <div class="table-wrap"><table>
      <tr><th>Date</th><th>Type</th><th>Details</th><th>Amount</th><th>Balance</th></tr>
      ${w.transactions.map((t) => {
        const plus = t.txn_type !== 'PAYMENT';
        return `<tr>
          <td>${esc(t.txn_time)}</td>
          <td><span class="status t-${esc(t.txn_type)}">${label[t.txn_type] || esc(t.txn_type)}</span></td>
          <td>${esc(t.description)}</td>
          <td class="${plus ? 'plus' : 'minus'}">${plus ? '+' : '−'}${money(t.amount)}</td>
          <td>${money(t.balance_after)}</td></tr>`;
      }).join('')}
    </table></div>` : '<p class="empty">No wallet activity yet.</p>';
}

async function topup(amount) {
  try {
    Object.assign(state.user, await api('/my/wallet/topup', 'POST', { amount }));
    updateBar();
    toast(money(amount) + ' added to your wallet');
    await loadWallet();
  } catch (e) { toast(e.message, true); }
}

$('#topup-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  await topup($('#topup-amt').value);
  $('#topup-amt').value = '';
});

// ===================== staff login =====================
$('#staff-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    const r = await api('/staff/login', 'POST', {
      staff_code: $('#staff-code').value,
      password: $('#staff-pass').value,
    });
    state.staffToken = r.token;
    state.staffInfo = r;
    state.staff = true;
    state.user = null; state.token = null;
    store.clear();
    $('#staff-pass').value = '';
    show('staff');
    updateBar();
    openTab('staff', 'sOrders');
  } catch (err) { toast(err.message, true); }
});

// ===================== staff: live orders =====================
function staffOrderCard(o) {
  let actions = '';
  if (o.status === 'Placed') actions += `<button class="btn good small" data-act="setStatus" data-id="${o.order_id}" data-status="Preparing">Start preparing</button>`;
  if (o.status === 'Preparing') actions += `<button class="btn good small" data-act="setStatus" data-id="${o.order_id}" data-status="Ready">Mark ready</button>`;
  if (o.status === 'Ready') actions += `<button class="btn good small" data-act="setStatus" data-id="${o.order_id}" data-status="Completed">Handed over</button>`;
  if (['Placed', 'Preparing', 'Ready'].includes(o.status))
    actions += `<button class="btn danger small" data-act="staffCancel" data-id="${o.order_id}">Cancel &amp; refund</button>`;
  const extras = fmtExtras(o.extras);
  return `
    <div class="order">
      <header>
        <h4>Token #${o.daily_no}${o.is_today == 1 ? '' : ' <small class="muted">(' + esc(o.order_time.split(',')[0]) + ')</small>'}</h4>
        <span class="status s-${esc(o.status)}">${esc(o.status)}</span>
      </header>
      <div class="muted small">${esc(o.student_name)} · ${esc(o.mobile)}</div>
      <div class="items">${esc(o.items)}</div>
      ${extras ? `<div class="extras">+ ${extras}</div>` : ''}
      ${o.special_note ? `<div class="note">📝 ${esc(o.special_note)}</div>` : ''}
      ${o.queue_position != null ? `<div class="queue">Queue position #${o.queue_position}</div>` : ''}
      ${refundLine(o)}
      <div class="meta"><span>${esc(o.order_time)}</span><b>${money(o.total_amount)}</b></div>
      ${actions ? `<div class="actions">${actions}</div>` : ''}
    </div>`;
}

async function loadStaffOrders() {
  const rows = await api('/admin/orders');
  $('#staff-orders').innerHTML = rows.length
    ? rows.map(staffOrderCard).join('')
    : '<p class="empty">No orders today yet.</p>';
}

// ===================== staff: inventory =====================
async function loadInventory() {
  const rows = await api('/menu');
  state.menu = rows;
  $('#inventory').innerHTML = `
    <div class="table-wrap"><table>
      <tr><th>Item</th><th>Category</th><th>Price (₹)</th><th>Stock</th><th>Status</th><th></th></tr>
      ${rows.map((m) => `
        <tr>
          <td>${esc(m.item_name)}</td>
          <td>${esc(m.category_name)}</td>
          <td><input type="number" step="0.5" min="0.5" id="p-${m.item_id}" value="${m.price}"></td>
          <td><input type="number" step="1" min="0" id="s-${m.item_id}" value="${m.stock}"></td>
          <td><span class="status ${m.is_available ? 's-Completed' : 's-Cancelled'}">${m.is_available ? 'Available' : 'Unavailable'}</span></td>
          <td><button class="btn dark small" data-act="saveItem" data-id="${m.item_id}">Save</button></td>
        </tr>`).join('')}
    </table></div>
    <p class="muted small">Set stock to 0 and the database trigger marks the item unavailable automatically. Changing a price is recorded in the audit log by a trigger.</p>`;
}

async function saveItem(id) {
  const m = state.menu.find((x) => x.item_id == id);
  const price = $('#p-' + id).value;
  const stock = $('#s-' + id).value;
  try {
    if (Number(price) !== Number(m.price)) await api('/admin/price', 'POST', { item_id: id, price });
    if (Number(stock) !== Number(m.stock)) await api('/admin/stock', 'POST', { item_id: id, stock });
    toast(m.item_name + ' updated');
  } catch (e) { toast(e.message, true); }
  await loadInventory();
}

// ===================== staff: add-on price fixing =====================
async function loadAddonsAdmin() {
  const rows = await api('/admin/customizations');
  state.addonsAdmin = rows;
  $('#addons-admin').innerHTML = `
    <div class="table-wrap"><table>
      <tr><th>Specification</th><th>Extra price (₹)</th><th>Shown to students</th><th></th></tr>
      ${rows.map((a) => `
        <tr>
          <td>${esc(a.custom_name)}</td>
          <td><input type="number" step="0.5" min="0" max="500" id="ap-${a.custom_id}" value="${a.extra_price}"></td>
          <td><span class="status ${a.is_active ? 's-Completed' : 's-Cancelled'}">${a.is_active ? 'Active' : 'Hidden'}</span></td>
          <td class="nowrap">
            <button class="btn dark small" data-act="saveAddon" data-id="${a.custom_id}">Save price</button>
            <button class="btn ghost-dark small" data-act="toggleAddonActive" data-id="${a.custom_id}">${a.is_active ? 'Hide' : 'Show'}</button>
          </td>
        </tr>`).join('')}
    </table></div>`;
}

async function saveAddon(id) {
  try {
    await api('/admin/custom-price', 'POST', { custom_id: id, price: $('#ap-' + id).value });
    toast('Price saved');
  } catch (e) { toast(e.message, true); }
  await loadAddonsAdmin();
}

$('#addon-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    await api('/admin/custom-add', 'POST', { name: $('#addon-name').value, price: $('#addon-price').value });
    $('#addon-name').value = ''; $('#addon-price').value = '';
    toast('Add-on added');
    await loadAddonsAdmin();
  } catch (err) { toast(err.message, true); }
});

// ===================== staff: daily audit log =====================
function todayString() {
  const d = new Date();
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

async function loadAudit() {
  const days = await api('/admin/audit-days');
  if (!state.auditDay) state.auditDay = days.length ? days[0].log_day : todayString();
  $('#audit-days').innerHTML = days.length
    ? days.map((d) => `<button class="day ${d.log_day === state.auditDay ? 'active' : ''}" data-act="auditDay" data-day="${esc(d.log_day)}">
        ${esc(d.log_day)} <small>${d.entries}</small></button>`).join('')
    : '<p class="muted small">No entries yet.</p>';
  const rows = await api('/admin/audit?date=' + encodeURIComponent(state.auditDay));
  $('#audit-title').textContent = 'Audit log for ' + state.auditDay;
  $('#audit').innerHTML = rows.length ? `
    <div class="table-wrap"><table>
      <tr><th>Time</th><th>Table</th><th>Action</th><th>Details</th></tr>
      ${rows.map((r) => `<tr class="${r.action === 'DAILY_SUMMARY' ? 'summary' : ''}">
        <td class="nowrap">${esc(r.at_time)}</td><td>${esc(r.table_name)}</td><td>${esc(r.action)}</td><td>${esc(r.description)}</td></tr>`).join('')}
    </table></div>` : '<p class="empty">Nothing was logged on this day.</p>';
}

// ===================== staff: feedback =====================
async function loadFeedback() {
  const f = await api('/admin/feedback');
  $('#feedback').innerHTML = Number(f.total) === 0
    ? '<p class="empty">No ratings yet.</p>'
    : `<div class="avg"><span class="big">${Number(f.average).toFixed(1)}</span> <span class="stars-static">${stars(Math.round(Number(f.average)))}</span>
       <span class="muted">from ${f.total} rating(s)</span></div>
       <div class="table-wrap"><table>
        <tr><th>Rating</th><th>Student</th><th>Order</th><th>Feedback</th><th>When</th></tr>
        ${f.recent.map((r) => `<tr>
          <td class="stars-static">${stars(Number(r.rating))}</td>
          <td>${esc(r.student_name)}</td>
          <td>Token #${r.daily_no} <small class="muted">${esc(r.order_day)}</small></td>
          <td>${esc(r.review_text)}</td>
          <td class="nowrap">${esc(r.rated_at)}</td></tr>`).join('')}
       </table></div>`;
}

// ===================== click + input handling =====================
const actions = {
  logout,
  authTab: (d) => setAuthMode(d.mode),
  authBack: () => show('auth'),
  fillDemo: () => { setAuthMode('login'); $('#login-mobile').value = '9876543210'; $('#login-pass').value = 'demo123'; },
  staffLogin: () => { show('stafflogin'); $('#staff-code').focus(); },

  add: (d) => { state.confirm = null; changeQty(d.id, 1); },
  inc: (d) => changeQty(d.id, 1),
  dec: (d) => changeQty(d.id, -1),
  toggleAddon: (d) => {
    const id = Number(d.id);
    state.extras = state.extras.includes(id) ? state.extras.filter((x) => x !== id) : [...state.extras, id];
    renderCart();
  },
  pay,
  fav: (d) => toggleFavourite(d.id),

  cancel: (d) => cancelOrder(d.id, d.refund === '1'),
  reorder: (d) => reorder(d.id),
  setStar: (d) => {
    const draft = (state.rateDraft[d.order] ||= { n: 0, text: '' });
    draft.n = Number(d.n);
    document.querySelectorAll(`.star[data-order="${d.order}"]`).forEach((b) => b.classList.toggle('on', Number(b.dataset.n) <= draft.n));
  },
  rate: (d) => submitRating(Number(d.order)),
  topup: (d) => topup(d.amt),

  setStatus: async (d) => {
    try { await api('/admin/order-status', 'POST', { order_id: d.id, status: d.status }); }
    catch (e) { toast(e.message, true); }
    await loadStaffOrders();
  },
  staffCancel: async (d) => {
    if (!confirm('Cancel this order and refund the student?')) return;
    try { await api('/admin/cancel', 'POST', { order_id: d.id }); toast('Order cancelled and refunded'); }
    catch (e) { toast(e.message, true); }
    await loadStaffOrders();
  },
  saveItem: (d) => saveItem(d.id),
  saveAddon: (d) => saveAddon(d.id),
  toggleAddonActive: async (d) => {
    try { await api('/admin/custom-toggle', 'POST', { custom_id: d.id }); }
    catch (e) { toast(e.message, true); }
    await loadAddonsAdmin();
  },
  auditDay: (d) => { state.auditDay = d.day; return loadAudit(); },
};

document.addEventListener('click', (e) => {
  const el = e.target.closest('[data-act]');
  if (!el) return;
  const fn = actions[el.dataset.act];
  if (fn) Promise.resolve(fn(el.dataset)).catch((err) => toast(err.message, true));
});

document.addEventListener('input', (e) => {
  if (e.target.id === 'note') state.note = e.target.value;
  if (e.target.classList.contains('rate-text')) {
    const id = e.target.dataset.order;
    (state.rateDraft[id] ||= { n: 0, text: '' }).text = e.target.value;
  }
});

// every second: refund countdown. every 10 seconds: live queue + new orders
setInterval(tickCountdowns, 1000);
setInterval(() => {
  const typing = document.activeElement && document.activeElement.classList.contains('rate-text');
  if (state.staff && state.tab === 'sOrders') loadStaffOrders().catch(() => {});
  if (state.token && state.tab === 'myorders' && !typing) loadMyOrders().catch(() => {});
}, 10000);

// ===================== start =====================
(async function boot() {
  loadAuthLists();
  const saved = store.get();
  if (saved) {                                  // student is still logged in on this device
    state.token = saved;
    try {
      state.user = await api('/me');
      show('student');
      updateBar();
      openTab('student', 'order');
      return;
    } catch (e) { state.token = null; store.clear(); }
  }
  show('auth');
})();
