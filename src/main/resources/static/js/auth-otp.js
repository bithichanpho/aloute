// Lucian UX/UI Prompt #026 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#026
(function () {
  'use strict';
  function create(input, {allowRecovery = false} = {}) {
    const root=document.createElement('div'); root.className='u026';
    const row=document.createElement('div'); row.className='u026-row';
    input.before(root); root.appendChild(row);
    const cells=[];
    for(let i=0;i<6;i++) {
      if(i===3) {const sep=document.createElement('i');sep.className='sep';sep.setAttribute('aria-hidden','true');row.appendChild(sep);}
      const cell=document.createElement('span');cell.className='u026-c';cell.style.setProperty('--i',i);cell.setAttribute('aria-hidden','true');row.appendChild(cell);cells.push(cell);
    }
    input.classList.add('u026-real'); row.appendChild(input);
    const status=document.createElement('p');status.className='u026-msg';status.id=input.id+'OtpStatus';status.setAttribute('role','status');root.appendChild(status);
    const descriptions=(input.getAttribute('aria-describedby')||'').split(/\s+/).filter(Boolean);
    input.setAttribute('aria-describedby',[...descriptions,status.id].join(' '));
    const reduced=()=>matchMedia('(prefers-reduced-motion: reduce)').matches;
    let recovery=false, checkingSince=0, phase='', toggle;
    const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
    function say(text){if(status.textContent!==text)status.textContent=text;}
    if(!allowRecovery) {
      input.addEventListener('beforeinput',event=>{
        if(!event.isComposing && event.inputType?.startsWith('insert') && event.data && /[^0-9]/.test(event.data)) {
          event.preventDefault();say('Mã OTP chỉ gồm 6 chữ số.');
        }
      });
      // Normalize before form feedback and legacy sanitizers read the value. Preserve the
      // caret when autofill/IME inserts text that could not be rejected in beforeinput.
      input.addEventListener('input',()=>{
        const value=input.value, normalized=value.replace(/[^0-9]/g,'').slice(0,6);
        if(value===normalized)return;
        const start=value.slice(0,input.selectionStart??value.length).replace(/[^0-9]/g,'').length;
        const end=value.slice(0,input.selectionEnd??value.length).replace(/[^0-9]/g,'').length;
        input.value=normalized;input.setSelectionRange(Math.min(start,6),Math.min(end,6));
      },true);
    }
    function paint() {
      const value=input.value;
      if(allowRecovery && value && !/^\d{0,6}$/.test(value))recovery=true;
      root.classList.toggle('is-recovery',recovery);
      root.classList.toggle('is-invalid',input.getAttribute('aria-invalid')==='true');
      cells.forEach((cell,i)=>{
        const ch=recovery?'':value[i]||'';
        if(cell.textContent!==ch) {
          cell.replaceChildren();
          if(ch){const digit=document.createElement('span');digit.textContent=ch;cell.appendChild(digit);}
        }
        cell.classList.toggle('has',!!ch);
        cell.classList.toggle('cur',!phase&&!recovery&&document.activeElement===input&&i===Math.min(input.selectionStart??value.length,5));
      });
      if(toggle){toggle.disabled=input.readOnly||input.disabled;toggle.textContent=recovery?'Dùng mã từ Authenticator':'Dùng mã khôi phục';toggle.setAttribute('aria-pressed',String(recovery));}
      input.inputMode=recovery?'text':'numeric';
    }
    function reset() {
      phase='';root.classList.remove('is-check','is-ok','is-bad');root.removeAttribute('aria-busy');
      paint();if(input.getAttribute('aria-invalid')!=='true')say(recovery?'Nhập mã khôi phục chưa sử dụng.':'Nhập mã gồm 6 chữ số.');
    }
    if(allowRecovery) {
      toggle=document.createElement('button');toggle.type='button';toggle.className='u026-toggle';root.appendChild(toggle);
      toggle.addEventListener('click',()=>{
        if(input.readOnly||input.disabled)return;
        recovery=!recovery;input.value='';input.dispatchEvent(new Event('input',{bubbles:true}));input.focus();
      });
    }
    input.addEventListener('input',()=>{reset();});
    for(const event of ['focus','blur','select','keyup','click'])input.addEventListener(event,paint);
    input.addEventListener('paste',event=>{
      if(input.readOnly||input.disabled||!event.clipboardData)return;
      event.preventDefault();
      let text=event.clipboardData.getData('text');
      if(!allowRecovery||(!recovery&&/^[\d\s]+$/.test(text)&&text.replace(/\D/g,'').length<=6))text=text.replace(/\D/g,'');
      if(!allowRecovery&&!text){say('Mã OTP chỉ gồm 6 chữ số.');return;}
      input.setRangeText(text,input.selectionStart??0,input.selectionEnd??input.value.length,'end');
      if(!allowRecovery)input.value=input.value.replace(/\D/g,'').slice(0,6);
      else input.value=input.value.slice(0,input.maxLength>0?input.maxLength:64);
      input.dispatchEvent(new Event('input',{bubbles:true}));
    });
    row.addEventListener('pointerdown',event=>{
      if(recovery||input.readOnly||input.disabled||event.button!==0)return;
      event.preventDefault();input.focus();
      let index=cells.findIndex(cell=>event.clientX<cell.getBoundingClientRect().right);
      if(index<0)index=5;
      index=Math.min(index,input.value.length);input.setSelectionRange(index,Math.min(index+1,input.value.length));paint();
    });
    new MutationObserver(paint).observe(input,{attributes:true,attributeFilter:['aria-invalid','readonly','disabled']});
    reset();
    return {
      messageElement:status,
      reset,
      checking(){checkingSince=Date.now();phase='checking';root.classList.remove('is-ok','is-bad');root.classList.add('is-check');root.setAttribute('aria-busy','true');say('Đang kiểm tra...');paint();},
      async settle(){if(phase==='checking')await wait(Math.max(0,(reduced()?60:640)-(Date.now()-checkingSince)));},
      async success(){phase='success';root.classList.remove('is-check','is-bad');root.classList.add('is-ok');root.removeAttribute('aria-busy');say('Xác thực thành công.');paint();await wait(reduced()?60:700);},
      async invalid(message='Mã không hợp lệ. Vui lòng thử lại.'){
        phase='invalid';root.classList.remove('is-check','is-ok');root.classList.add('is-bad');root.removeAttribute('aria-busy');say(message);paint();
        await wait(reduced()?600:900);
        // The form remains locked during this animation, so a later response cannot erase a new entry.
        if(!recovery)input.value='';
        phase='';root.classList.remove('is-bad');paint();input.focus({preventScroll:true});if(recovery)input.select();
      }
    };
  }
  window.AlouteOtp={create};
})();
