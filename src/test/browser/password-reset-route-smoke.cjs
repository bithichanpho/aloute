const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');

// Read-only checks against a running local backend. No fixtures, credentials or POSTs.
(async()=>{
  const origin=new URL(process.env.ALOUTE_BASE_URL||'http://localhost:8081');
  assert.ok(['localhost','127.0.0.1','[::1]'].includes(origin.hostname),'Local backend only');
  assert.equal(origin.protocol,'http:');
  assert.equal(origin.username,'');assert.equal(origin.password,'');
  assert.equal(origin.pathname,'/');assert.equal(origin.search,'');assert.equal(origin.hash,'');
  const results=[];
  for(const [route,expectedStatus,marker,destination] of [
    ['/forgot-password',200,'id="forgotForm"'],
    ['/verify-reset-code',200,'id="resetCodeForm"'],
    ['/reset-password',302,null,'/verify-reset-code'],
    ['/feed',302,null,'/login']
  ]) {
    const result={route,expectedStatus};
    try {
      const response=await fetch(new URL(route,origin),{redirect:'manual',signal:AbortSignal.timeout(8000)});
      const body=await response.text();
      result.status=response.status;
      const location=response.headers.get('location');
      if(location) {
        const target=new URL(location,origin);result.destination=target.pathname;
        assert.equal(target.origin,origin.origin,'Redirect must stay on the same local backend');
      }
      assert.equal(response.status,expectedStatus);
      if(marker)assert.ok(body.includes(marker),'Expected auth form must be served by the real controller');
      if(route==='/verify-reset-code')assert.ok(!body.includes('type="password"'),'OTP before password');
      if(destination)assert.equal(result.destination,destination);
      result.passed=true;
    }catch(error){result.passed=false;result.error=error.message;}
    results.push(result);
  }
  const report={origin:origin.origin,readOnly:true,passed:results.filter(r=>r.passed).length,failed:results.filter(r=>!r.passed).length,results};
  if(process.env.ALOUTE_AUTH_EVIDENCE) {
    const output=process.env.ALOUTE_AUTH_EVIDENCE;fs.mkdirSync(output,{recursive:true});
    fs.writeFileSync(path.join(output,process.argv.includes('before')?'runtime-before.json':'runtime-after.json'),JSON.stringify(report,null,2));
  }
  console.log(JSON.stringify(report));if(report.failed)process.exitCode=1;
})().catch(error=>{console.error(error);process.exitCode=1;});
