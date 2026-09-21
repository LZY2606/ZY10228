const $ = id => document.getElementById(id);
const json = (r) => r.json();
async function get(u){return (await fetch(u)).json();}
async function post(u,body,type){
  const opt={method:'POST',headers:{'Content-Type':type||'application/json'}};
  if(body!==undefined) opt.body = (type==='text')? body : JSON.stringify(body);
  return (await fetch(u,opt)).json();
}
let STATE={grids:[],ems:[],samples:[],selectedEms:new Set(),last:null};

function fmt(x,n){n=n??4;if(x===null||x===undefined)return'-';if(Math.abs(x)>=1e4||(Math.abs(x)>0&&Math.abs(x)<1e-3))return x.toExponential(3);return Number(x).toFixed(n);}

async function loadAll(){
  STATE.grids = await get('/api/grids');
  STATE.ems = await get('/api/endmembers');
  STATE.samples = await get('/api/samples');
  // samples
  const s=$('sample'); s.innerHTML='';
  STATE.samples.forEach(x=>{const o=document.createElement('option');o.value=x.id;o.textContent=x.name;s.appendChild(o);});
  if(STATE.grids[0]){const g=$('grid');g.innerHTML='';STATE.grids.forEach(x=>{const o=document.createElement('option');o.value=x.name;o.textContent=x.name+' ('+x.edgesUm[0]+'–'+x.edgesUm[x.edgesUm.length-1]+' µm, '+x.size+'箱)';g.appendChild(o);});
    g.value=STATE.grids.find(x=>x.name==='common-log-4per-decade')? 'common-log-4per-decade':g.value;
  }
  const e=$('ems'); e.innerHTML='';
  STATE.ems.forEach(m=>{
    const lab=document.createElement('label');
    const c=document.createElement('input');c.type='checkbox';c.value=m.name;c.checked=true;
    STATE.selectedEms.add(m.name);
    c.onchange=()=>{c.checked?STATE.selectedEms.add(m.name):STATE.selectedEms.delete(m.name);};
    lab.appendChild(c); lab.appendChild(document.createTextNode(m.name+' ['+m.basis+']'));
    e.appendChild(lab);
  });
  renderRaw();
  loadRuns();
}

function renderRaw(){
  const sid=parseInt($('sample').value); const s=STATE.samples.find(x=>x.id===sid);
  const host=$('rawTables'); host.innerHTML='';
  if(!s)return;
  s.observations.forEach(o=>{
    const div=document.createElement('div');div.style.flex='1';div.style.minWidth='320px';div.style.marginRight='10px';
    let rows=o.bins.map((b,i)=>'<tr><td>'+(i+1)+'</td><td>'+fmt(b.loUm,3)+'</td><td>'+fmt(b.hiUm,3)+(i===o.bins.length-1?' <span class="tag warn">右端点</span>':'')+'</td><td>'+fmt(b.value,4)+'</td></tr>').join('');
    const total=o.bins.reduce((a,b)=>a+b.value,0);
    div.innerHTML='<div class="muted">'+o.instrument+' · '+o.basis+' · ρ='+o.density+' · RI='+o.riReal+'+'+o.riImag+'i</div>'+
      '<table><thead><tr><th>#</th><th>lo µm [</th><th>hi µm )</th><th>value</th></tr></thead><tbody>'+rows+
      '</tbody><tfoot><tr><th>合计</th><th></th><th></th><th>'+fmt(total,4)+'</th></tr></tfoot></table>';
    host.appendChild(div);
  });
}

// ---------- 画布 ----------
function setup(cv){const dpr=window.devicePixelRatio||1;const w=cv.clientWidth,h=cv.clientHeight;cv.width=w*dpr;cv.height=h*dpr;const ctx=cv.getContext('2d');ctx.scale(dpr,dpr);return {ctx,w,h};}
function drawSeries(cv,series,xLabels,opts){
  opts=opts||{};
  const {ctx,w,h}=setup(cv);ctx.clearRect(0,0,w,h);
  const ml=58,mr=12,mt=14,mb=34;const pw=w-ml-mr,ph=h-mt-mb;
  let max=1e-9,min=opts.allowNegative?1e9:0;
  series.forEach(s=>s.y.forEach(v=>{max=Math.max(max,v);if(opts.allowNegative)min=Math.min(min,v);}));
  if(opts.allowNegative&&min>0)min=0;
  ctx.strokeStyle='#cbd5e0';ctx.beginPath();ctx.moveTo(ml,mt);ctx.lineTo(ml,mt+ph);ctx.lineTo(ml+pw,mt+ph);ctx.stroke();
  ctx.fillStyle='#4a5568';ctx.font='10px sans-serif';
  for(let g=0;g<=4;g++){const yv=min+(max-min)*(1-g/4);const y=mt+ph*g/4;ctx.fillText(fmt(yv,3),4,y+3);if(g>0){ctx.strokeStyle='#eef2f7';ctx.beginPath();ctx.moveTo(ml,y);ctx.lineTo(ml+pw,y);ctx.stroke();}}
  const n=xLabels.length;
  for(let t=0;t<n;t+=Math.ceil(n/10)){const x=ml+pw*t/(n-1);ctx.fillText(fmt(xLabels[t],2),x-12,mt+ph+14);}
  const colors=['#2b6cb0','#dd6b20','#38a169','#805ad5','#c53030'];
  series.forEach((s,si)=>{
    ctx.strokeStyle=colors[si%colors.length];ctx.fillStyle=colors[si%colors.length];ctx.lineWidth=si===0?2:1.4;
    ctx.beginPath();
    s.y.forEach((v,t)=>{const x=ml+pw*t/(n-1);const y=mt+ph-(v-min)/((max-min)||1)*ph;t?ctx.lineTo(x,y):ctx.moveTo(x,y);});
    ctx.stroke();
    if(s.fill){ctx.lineTo(ml+pw,mt+ph);ctx.lineTo(ml,mt+ph);ctx.globalAlpha=.08;ctx.fill();ctx.globalAlpha=1;}
  });
  let lx=ml+8;
  series.forEach((s,si)=>{ctx.fillStyle=colors[si%colors.length];ctx.fillRect(lx,2,10,10);ctx.fillStyle='#2d3748';ctx.fillText(s.name,lx+14,11);lx+=ctx.measureText(s.name).width+34;});
}

function solve(){
  const req={sampleId:parseInt($('sample').value),gridName:$('grid').value,targetBasis:$('basis').value,
    densityModel:$('densityModel').value,uniformDensity:parseFloat($('density').value),
    riReal:parseFloat($('riReal').value),riImag:parseFloat($('riImag').value),
    endmemberNames:[...STATE.selectedEms],save:true};
  post('/api/solve',req).then(r=>{STATE.last=r;renderResult(r);loadRuns();}).catch(e=>alert('求解失败: '+e));
}

function renderResult(r){
  // 投影图：每个仪器 converted（目标口径）
  const centers=r.result.gridCentersUm;
  const series=r.views.map((v,i)=>({name:v.instrument+'→'+r.result.targetBasis,y:v.converted,fill:false}));
  drawSeries($('cProj'),series,centers);
  // 拟合/残差（堆叠行）：把多仪器按顺序画在同一轴上（观测点 + 拟合线）
  const obs=r.result.observedConverted, fit=r.result.fitted, res=r.result.residual;
  drawSeries($('cFit'),[{name:'观测(堆叠)',y:obs},{name:'拟合',y:fit}],obs.map((_,i)=>i),{});
  drawSeries($('cRes'),[{name:'残差 拟合-观测',y:res}],res.map((_,i)=>i),{allowNegative:true});

  // 守恒
  let ch='<table><thead><tr><th>仪器</th><th>原始总量</th><th>网格内</th><th>below</th><th>above</th><th>未覆盖</th><th>最后右端点 µm</th><th>守恒</th></tr></thead><tbody>';
  r.verification.totalChecks.forEach(t=>{
    ch+='<tr><td>'+t.instrument+'</td><td>'+fmt(t.totalRaw)+'</td><td>'+fmt(t.onGrid)+'</td><td>'+fmt(t.below)+'</td><td>'+fmt(t.above)+'</td><td>'+fmt(t.below+t.above)+'</td><td class="pill">'+fmt(t.lastEdgeUm,3)+'</td><td>'+(t.conserved?'<span class="tag ok">守恒</span>':'<span class="tag err">失败</span>')+'</td></tr>';
  });
  ch+='</tbody></table><div class="muted" style="margin-top:6px">'+r.verification.message+'（run id：'+(r.runId??'-')+'）</div>';
  $('conserve').innerHTML=ch;

  // 可识别性
  const id=r.result.identifiability;
  let ih='<div class="kv"><b>最小|相关|</b><span>'+fmt(id.minAbs,3)+'</span><b>近相关对 |r|≥0.90</b><span>'+
    (id.nearCollinearPairs.length? id.nearCollinearPairs.map(p=>p.a+' ↔ '+p.b+' (r='+fmt(p.corr,3)+')').join('；'):'无')+
    '</span><b>条件数(近似)</b><span>'+fmt(id.conditionNumber,2)+'</span><b>候选簇数</b><span>'+r.result.clusters.length+'</span></div>'+
    '<div style="margin-top:6px"><span class="tag '+(id.nearCollinearPairs.length?'warn':'ok')+'">诊断</span>'+id.note+'</div>';
  $('ident').innerHTML=ih;

  // 候选
  const names=[...Object.keys(r.result.best.proportions)];
  let ch2='<table><thead><tr><th>#</th><th>簇</th><th>活跃端元</th>';
  names.forEach(n=>ch2+='<th>'+n+'</th>');
  ch2+='<th>SSE</th><th>RMSE</th><th>BIC</th></tr></thead><tbody>';
  r.result.candidates.forEach((c,i)=>{
    ch2+='<tr '+(i===0?'style="background:#ebf4ff"':'')+'><td>'+(i+1)+'</td><td>'+c.clusterId+'</td><td style="text-align:left">'+c.activeNames.join('+')+'</td>';
    names.forEach(n=>ch2+='<td>'+fmt(c.proportions[n],4)+'</td>');
    ch2+='<td>'+fmt(c.sse,3)+'</td><td>'+fmt(c.rmse,4)+'</td><td>'+fmt(c.bic,2)+'</td></tr>';
  });
  ch2+='</tbody></table>';
  ch2+='<div class="muted" style="margin-top:6px">近相关端元会产生 SSE 接近但比例不同的多个稀疏候选；它们按余弦相似度≥0.985 归簇，请并列查看，勿只取一个“小数位稳定”的比例。</div>';
  $('cands').innerHTML=ch2;
}

async function addEndmember(){
  const vals=$('em_vals').value.split(',').map(s=>parseFloat(s.trim())).filter(x=>!isNaN(x));
  const varr=$('em_var').value? $('em_var').value.split(',').map(s=>parseFloat(s.trim())):null;
  const body={name:$('em_name').value.trim(),basis:$('em_basis').value,density:parseFloat($('em_density').value),
    riReal:parseFloat($('em_ri').value),riImag:0.01,values:vals,diagVariance:varr};
  const r=await post('/api/endmembers',body);alert('已保存端元 id='+r.id);loadAll();
}
async function addObservation(){
  const bins=$('ob_bins').value.trim().split(/\n/).map(l=>{const p=l.split(',').map(x=>parseFloat(x.trim()));return {loUm:p[0],hiUm:p[1],value:p[2]};});
  const body={sampleName:$('ob_sample').value.trim(),instrument:$('ob_inst').value,basis:$('ob_basis').value,
    density:2.65,riReal:1.54,riImag:0.01,bins};
  const r=await post('/api/observations',body);alert('已保存 observation id='+r.observationId);loadAll();
}
function exp(){window.location='/api/export';}
async function resetFix(){const r=await post('/api/reset-fixtures');alert('已重置：'+JSON.stringify(r.counts));loadAll();}
async function reimport(){
  const f=$('importFile').files[0];if(!f){alert('先选择导出的 JSON 文件');return;}
  const text=await f.text();
  const r=await post('/api/reimport',text,'text');
  let h='<span class="tag '+(r.allVerified?'ok':'err')+'">'+(r.allVerified?'全部核对通过':'存在不一致')+'</span>';
  h+='<div class="muted">导入：'+JSON.stringify(r.imported)+'</div>';
  r.runVerifications.forEach(v=>{h+='<div class="muted">run#'+(v.runId??'-')+' SSE 重算='+fmt(v.sse,4)+' 期望='+fmt(v.expectedSse,4)+' '+(v.matched?'<span class="tag ok">SSE匹配</span>':'<span class="tag err">SSE不符</span>')+' '+(v.conservationOk?'<span class="tag ok">守恒</span>':'<span class="tag err">失量</span>')+'</div>';});
  $('importReport').innerHTML=h;loadAll();
}
async function loadRuns(){
  const rs=await get('/api/runs');let h='';
  rs.forEach(r=>{h+='<div class="cand"><b>#'+r.id+'</b> <span class="muted">'+r.createdAt+'</span><br/>'+
    '<span class="muted">'+r.assumptions.gridName+' · '+r.assumptions.targetBasis+' · '+r.assumptions.densityModel+' · RI '+r.assumptions.riReal+'+'+r.assumptions.riImag+'i</span><br/>'+
    'SSE='+fmt(r.best.sse,3)+' 候选簇='+r.clusterCount+' 失量='+fmt(r.obsUncovered,3)+'/'+fmt(r.obsTotalRaw,3)+'</div>';});
  $('runs').innerHTML=h||'<div class="muted">暂无运行记录</div>';
}

$('sample').onchange=renderRaw;
loadAll();
