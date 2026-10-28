// Reusable six-digit OTP input for email verification and password reset (not 2FA).
(function () {
  'use strict';
  function create(input) {
    const root=document.createElement('div'); root.className='u026';
    const row=document.createElement('div'); row.className='u026-row';
    // The original form control remains the single source of truth, but is visually hidden
    // over the six decorative cells so it does not render as an extra seventh input.
    input.classList.add('u026-real');
    input.maxLength=6;
    input.inputMode='numeric';
    input.autocomplete=input.autocomplete || 'one-time-code';
    input.setAttribute('aria-label', input.getAttribute('aria-label') || 'Mã OTP gồm 6 chữ số');
    input.before(root); root.appendChild(row);
    const cells=[];
    for(let i=0;i<6;i++) {
      if(i===3) {const sep=document.createElement('i');sep.className='sep';sep.setAttribute('aria-hidden','true');row.appendChild(sep);}
      const cell=document.createElement('span');cell.className='u026-c';cell.style.setProperty('--i',i);cell.setAttribute('aria-hidden','true');row.appendChild(cell);cells.push(cell);
    }
    row.appendChild(input);
    const status=document.createElement('p');status.className='u026-msg';status.id=input.id+'OtpStatus';status.setAttribute('role','status');root.appendChild(status);
    const descriptions=(input.getAttribute('aria-describedby')||'').split(/\s+/).filter(Boolean);
    input.setAttribute('aria-describedby',[...descriptions,status.id].join(' '));
    const reduced=()=>matchMedia('(prefers-reduced-motion: reduce)').matches;
    let checkingSince=0, phase='';
    const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
    function say(text){if(status.textContent!==text)status.textContent=text;}
    input.addEventListener('beforeinput',event=>{
      if(!event.isComposing && event.inputType?.startsWith('insert') && event.data && /[^0-9]/.test(event.data)) {
        event.preventDefault();say('Mã OTP chỉ gồm 6 chữ số.');
      }
    });
    input.addEventListener('input',()=>{
      const value=input.value, normalized=value.replace(/[^0-9]/g,'').slice(0,6);
      if(value!==normalized) {
        const start=value.slice(0,input.selectionStart??value.length).replace(/[^0-9]/g,'').length;
        const end=value.slice(0,input.selectionEnd??value.length).replace(/[^0-9]/g,'').length;
        input.value=normalized;input.setSelectionRange(Math.min(start,6),Math.min(end,6));
      }
      reset();
    },true);
    function paint() {
      const value=input.value;
      root.classList.toggle('is-invalid',input.getAttribute('aria-invalid')==='true');
      cells.forEach((cell,i)=>{
        const ch=value[i]||'';
        if(cell.textContent!==ch) {cell.replaceChildren();if(ch){const digit=document.createElement('span');digit.textContent=ch;cell.appendChild(digit);}}
        cell.classList.toggle('has',!!ch);
        cell.classList.toggle('cur',!phase&&document.activeElement===input&&i===Math.min(input.selectionStart??value.length,6));
      });
      input.inputMode='numeric';
    }
    function reset() {
      phase='';root.classList.remove('is-check','is-ok','is-bad');root.removeAttribute('aria-busy');
      paint();if(input.getAttribute('aria-invalid')!=='true')say('Nhập mã gồm 6 chữ số.');
    }
    for(const event of ['focus','blur','select','keyup','click'])input.addEventListener(event,paint);
    input.addEventListener('paste',event=>{
      if(input.readOnly||input.disabled||!event.clipboardData)return;
      event.preventDefault();
      const text=event.clipboardData.getData('text').replace(/[^0-9]/g,'').slice(0,6);
      if(!text){say('Mã OTP chỉ gồm 6 chữ số.');return;}
      input.setRangeText(text,input.selectionStart??0,input.selectionEnd??input.value.length,'end');
      input.value=input.value.replace(/[^0-9]/g,'').slice(0,6);
      input.dispatchEvent(new Event('input',{bubbles:true}));
    });
    row.addEventListener('pointerdown',event=>{
      if(input.readOnly||input.disabled||event.button!==0)return;
      event.preventDefault();input.focus();
      let index=cells.findIndex(cell=>event.clientX<cell.getBoundingClientRect().right);
      if(index<0)index=5;
      index=Math.min(index,5,input.value.length);input.setSelectionRange(index,Math.min(index+1,input.value.length));paint();
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
        input.value='';phase='';root.classList.remove('is-bad');paint();input.focus({preventScroll:true});
      }
    };
  }
  window.AlouteOtp={create};
})();
