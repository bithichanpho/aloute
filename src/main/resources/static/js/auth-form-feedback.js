// Lucian UX/UI Prompt #244 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#244
(function () {
  'use strict';
  function focus(target, select) {
    if (!target) return;
    target.scrollIntoView({block:'center', behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});
    target.focus({preventScroll:true});
    if (select && typeof target.select === 'function') target.select();
  }

  function create(form, errorBox, definitions) {
    const fields = new Map();
    let general = '', generalTarget = null;
    errorBox.setAttribute('role', 'alert');
    errorBox.tabIndex = -1;
    for (const [id, definition] of Object.entries(definitions)) {
      const input = form.querySelector('#' + id);
      if (!input) continue;
      const wrapper = input.closest('.field');
      const label = document.createElement('label');
      label.htmlFor = id; label.className = 'auth-field-label'; label.textContent = definition.label;
      wrapper.insertBefore(label, input);
      const hint = document.createElement('div'); hint.className = 'auth-field-hint'; hint.setAttribute('aria-hidden','true');
      const inner = document.createElement('div'); const text = document.createElement('span'); text.id = id+'Feedback';
      inner.appendChild(text); hint.appendChild(inner); wrapper.appendChild(hint);
      const field = {input, wrapper, hint, text, definition, message:'', server:false}; fields.set(id, field);
      input.addEventListener('input', () => {
        if (field.grouped) { clear(); return; }
        if (field.inlineTarget) general='';
        if (field.message && field.server) { general=''; setField(field,'',false); }
        fields.forEach(other => { if(other.message && !other.server) setField(other,other.definition.validate(),false); });
        render();
      });
    }
    function setField(field, message, server, grouped = false) {
      field.message = message || ''; field.server = !!server; field.grouped = grouped;
      field.wrapper.classList.toggle('is-invalid', !!message);
      field.input.setAttribute('aria-invalid', String(!!message));
      const inline = field.inlineTarget;
      field.hint.style.display = grouped || inline ? 'none' : '';
      field.hint.setAttribute('aria-hidden', String(!message || grouped || !!inline));
      field.text.textContent = grouped || inline ? '' : message || '';
      if (inline) inline.textContent = message || '';
      const ids = (field.input.getAttribute('aria-describedby') || '').split(/\s+/).filter(id => id && id !== field.feedbackDescription);
      field.feedbackDescription = message && !inline ? grouped ? errorBox.id : field.text.id : null;
      if (inline && !ids.includes(inline.id)) ids.push(inline.id);
      if (field.feedbackDescription && !ids.includes(field.feedbackDescription)) ids.push(field.feedbackDescription);
      if (ids.length) field.input.setAttribute('aria-describedby', ids.join(' '));
      else field.input.removeAttribute('aria-describedby');
    }
    function render() {
      errorBox.replaceChildren();
      if (generalTarget) {
        errorBox.style.display = 'none';
        if (general) generalTarget.textContent = general;
        return;
      }
      const invalid = [...fields.values()].filter(field => field.message && !field.grouped);
      errorBox.style.display = invalid.length || general ? 'block' : 'none';
      if (general) errorBox.appendChild(document.createTextNode(general));
      else if (invalid.length) errorBox.appendChild(document.createTextNode(invalid.length + ' trường cần kiểm tra.'));
      if (invalid.length) {
        const list = document.createElement('ul');
        for (const field of invalid) {
          const item = document.createElement('li'); const link = document.createElement('a');
          link.href = '#'+field.input.id; link.textContent = field.definition.label;
          link.addEventListener('click', event => { event.preventDefault(); focus(field.input, true); });
          item.appendChild(link); list.appendChild(item);
        }
        errorBox.appendChild(list);
      }
    }
    function clear() { general=''; fields.forEach(field => setField(field,'',false)); render(); }
    function show(message, id) {
      clear();
      const field=fields.get(id);
      general=field?.inlineTarget ? '' : message;
      if (field) { setField(field,message,true); shake(field); }
      render(); focus(field ? field.input : generalTarget || errorBox, !!field);
    }
    function showGroup(message, ids) {
      clear(); general=message;
      const invalid=ids.map(id=>fields.get(id)).filter(Boolean);
      invalid.forEach(field=>{setField(field,message,true,true);shake(field);});
      render(); focus(invalid[0]?.input || errorBox, !!invalid.length);
    }
    function shake(field) {
      field.wrapper.classList.remove('is-shake'); void field.wrapper.offsetWidth; field.wrapper.classList.add('is-shake');
    }
    function validate() {
      clear(); let first;
      fields.forEach(field => {
        const message=field.definition.validate();
        if(message) {setField(field,message,false); shake(field); first ||= field;}
      });
      render(); if(first)focus(first.input,true); return !first;
    }
    function useInlineFeedback(id, target) {
      const field=fields.get(id);
      if (!field || !target?.id) return;
      field.inlineTarget=target;
      setField(field,field.message,field.server,field.grouped);
    }
    function setGeneralFeedbackTarget(target) {
      generalTarget=target || null;
      if (generalTarget) generalTarget.tabIndex=-1;
      render();
    }
    return {clear,show,showGroup,validate,focus,useInlineFeedback,setGeneralFeedbackTarget};
  }

  function emailError(value) {
    value=value.trim(); if(!value)return 'Vui lòng nhập email.';
    const at=value.lastIndexOf('@'), local=value.slice(0,at), domain=value.slice(at+1);
    return value.length>254 || at<1 || local.length>64 || !domain || /[\s@]/u.test(domain)
      || domain.startsWith('.') || domain.endsWith('.') || domain.includes('..') || /[\r\n]/.test(local)
      || (!local.includes('"') && /[\s@]/u.test(local)) ? 'Email không đúng định dạng.' : '';
  }
  function passwordError(value) {
    if(new TextEncoder().encode(value).length>72)return 'Mật khẩu không được vượt quá 72 byte UTF-8.';
    return value.length<8 || value.length>72 || !/\p{Uppercase}/u.test(value) || !/\p{Lowercase}/u.test(value)
      || !/\p{Nd}/u.test(value) || !/[^\p{L}\p{Nd}\u0009-\u000D\u001C-\u0020\u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]/u.test(value)
      ? 'Mật khẩu phải có 8–72 ký tự, gồm chữ hoa, chữ thường, số và ký tự đặc biệt.' : '';
  }
  async function post(path, body) {
    const controller=new AbortController(); let timer;
    const timeout=new Promise((_,reject)=>{timer=setTimeout(()=>{
      reject(new Error('Yêu cầu quá thời gian chờ, chưa xác định được kết quả. Vui lòng thử lại sau.')); controller.abort();
    },30000);});
    try{return await Promise.race([Aloute.api(path,{method:'POST',body,signal:controller.signal}),timeout]);}
    finally{clearTimeout(timer);}
  }
  function lock(form, active) {
    for(const input of form.querySelectorAll('input')) {
      if(active) {input.dataset.authWasReadonly=String(input.readOnly); input.readOnly=true;}
      else {input.readOnly=input.dataset.authWasReadonly==='true'; delete input.dataset.authWasReadonly;}
    }
    if(active)form.setAttribute('aria-busy','true'); else form.removeAttribute('aria-busy');
  }
  window.AlouteAuthForms={create,focus,emailError,passwordError,post,lock};
})();
