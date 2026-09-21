let EMS = [], SAMPLES = [], LAST = null;
const $ = id => document.getElementById(id);
function toast(m, bad){ const t=$('toast'); t.textContent=m; t.style.background=bad?'#8a2c22':'#223'; t.style.opacity=1; setTimeout(()=>t.style.opacity=0, 3500); }
async function api(path, opt){ const r = await fetch(path, opt); if(!r.ok) throw new Error(await r.text()); return r.status===200?r.json():null; }
const fmt = (x,n=4) => { if(x===null||x===undefined) return '-'; if(!isFinite(x)) return '∞'; const a=Math.abs(x); if(a!==0&&(a<1e-4||a>=1e6)) return x.toExponential(2); return x.toFixed(n); };
function tag(b){ return `<span class="tag ${b.toLowerCase()}">${ {MASS:'质量 mass',VOLUME:'体积 volume',AREA:'面积 area',NUMBER:'数量 number'}[b]||b }</span>`; }

async function refresh(){
  EMS = await api('/api/endmembers'); SAMPLES = await api('/api/samples');
  const solves = await api('/api/solves');
  $('sample').innerHTML = SAMPLES.map(s=>`<option value="${s.id}">${s.name}</option>`).join('');
  $('endmembers').innerHTML = EMS.map(e=>`<option value="${e.id}" selected>${e.name}</option>`).join('');
  $('emList').innerHTML = `<table><tr><th>ID</th><th>名称</th><th>口径</th><th>箱数</th><th>σ</th><th>相关长</th><th></th></tr>` +
    EMS.map(e=>`<tr><td>${e.id}</td><td style="text-align:left">${e.name}</td><td>${tag(e.distribution.basis)}</td>
      <td>${e.distribution.values.length}</td><td>${e.relSigma.length?fmt(e.relSigma[0],3):'默认'}</td>
      <td>${fmt(e.corrLength,1)}</td><td><button class="danger" onclick="delEm('${e.id}')">删</button></td></tr>`).join('') + `</table>`;
  $('sampleList').innerHTML = SAMPLES.map(s=>`<div style="margin:6px 0"><b>${s.name}</b> <span class="pill">${s.blendMode}</span>
      ${s.instruments.map(i=>` <span class="pill">${i.instrument} ${tag(i.basis)} ${i.values.length}箱 [${fmt(i.edges[0],3)},${fmt(i.edges[i.edges.length-1],1)})</span>`).join('')}</div>`).join('');
  $('solveList').innerHTML = solves.length ? `<table><tr><th>#</th><th>样品</th><th>时间</th><th>候选/簇</th><th></th></tr>` +
    solves.map(r=>{ let c='-'; try{ c=(JSON.parse(r.resultJson).candidates.length||'-')+'/'+JSON.parse(r.resultJson).clusters.length; }catch(e){}
      return `<tr><td>${r.id}</td><td style="text-align:left">${r.sampleId}</td><td>${r.createdAt.replace('T',' ').slice(0,19)}</td><td>${c}</td>
      <td><button class="sec" onclick="loadSolve(${r.id})">查看</button><button class="ok" onclick="replay(${r.id})">重放</button></td></tr>`; }).join('')+'</table>'
    : '<p class="muted">暂无运行记录</p>';
}
async function delEm(id){ await api('/api/endmembers/'+id,{method:'DELETE'}); refresh(); }
async function addEndmember(){
  try { const o=JSON.parse($('emJson').value); await api('/api/endmembers',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(o)}); $('emJson').value=''; toast('端元已保存'); refresh(); }
  catch(e){ toast(e.message,true); }
}
async function addSample(){
  try { const o=JSON.parse($('sJson').value); await api('/api/samples',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(o)}); $('sJson').value=''; toast('样品已保存'); refresh(); }
  catch(e){ toast(e.message,true); }
}
function selectedOptions(sel){ return [...sel.selectedOptions].map(o=>o.value); }
async function runSolve(){
  const body = {
    sampleId: $('sample').value, endmemberIds: selectedOptions($('endmembers')),
    grid:{ minD:+$('minD').value, maxD:+$('maxD').value, binsPerDecade:+$('bpd').value },
    conversion:{ fromBasis:'MASS', toBasis:$('toBasis').value, density:+$('density').value, refractiveIndex:+$('ri').value, modelName:'SPHERICAL_MOMENT' },
    blendMode:$('blend').value, maxCandidates:+$('maxCand').value, maxSubsetSize:+$('subset').value,
    relSigma:+$('relSigma').value, corrLength:+$('corrLen').value
  };
  try { const r=await api('/api/solve',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    LAST=r.result; render(LAST); toast('已保存运行记录 #'+r.solveId); refresh(); }
  catch(e){ toast(e.message,true); }
}
async function loadSolve(id){ const r=await api('/api/solves/'+id); LAST=JSON.parse(r.resultJson); render(LAST); toast('已载入 #'+id); }
async function replay(id){
  try{ const r=await api('/api/solves/'+id+'/replay',{method:'POST'});
    toast(`重放 #${id}: ${r.matched?'一致 ✓':'不一致 ✗'} 最大差 ${fmt(r.maxAbsDiff,3)}`, !r.matched); }
  catch(e){ toast(e.message,true); }
}
function exportBundle(){ window.location='/api/export'; }
async function importBundle(inp){
  const f=inp.files[0]; if(!f) return; const text=await f.text();
  try{ JSON.parse(text); await api('/api/import',{method:'POST',headers:{'Content-Type':'application/json'},body:text}); toast('导入完成，可重放复核'); refresh(); }
  catch(e){ toast('导入失败: '+e.message,true); }
  inp.value='';
}
async function resetDb(){ if(!confirm('清空数据库并恢复固定 fixture？')) return; await api('/api/reset',{method:'POST'}); LAST=null; await refresh(); toast('已重置'); }

/* ---------- SVG 图表（对数 x 轴柱状） ---------- */
function barChart(series, edges, opts={}){
  const W=760,H=220,pad={l:52,r:12,t:14,b:34};
  const logMin=Math.log10(edges[0]), logMax=Math.log10(edges[edges.length-1]);
  const X=d=>(pad.l+(Math.log10(d)-logMin)/(logMax-logMin)*(W-pad.l-pad.r));
  let mx=0; series.forEach(s=>s.data.forEach(v=>mx=Math.max(mx,v))); mx=mx||1;
  const Y=v=>H-pad.b-v/mx*(H-pad.t-pad.b);
  let out='';
  for(let p2=Math.ceil(logMin);p2<=Math.floor(logMax);p2++){ const x=X(Math.pow(10,p2));
    out+=`<line x1="${x}" y1="${pad.t}" x2="${x}" y2="${H-pad.b}" stroke="#eee"/>
          <text x="${x}" y="${H-pad.b+15}" font-size="10" fill="#888" text-anchor="middle">10^${p2}</text>`; }
  [0,.25,.5,.75,1].forEach(q=>{ const y=pad.t+q*(H-pad.t-pad.b);
    out+=`<line x1="${pad.l}" y1="${y}" x2="${W-pad.r}" y2="${y}" stroke="#f3f3f3"/>
          <text x="${pad.l-5}" y="${y+3}" font-size="9" fill="#999" text-anchor="end">${fmt(mx*(1-q),2)}</text>`; });
  series.forEach(s=>{
    s.data.forEach((v,i)=>{ const x1=X(edges[i]), x2=X(edges[i+1]);
      if(!opts.overlay && v<=0) return;
      out+=`<rect x="${x1+0.4}" y="${Y(v)}" width="${Math.max(0.5,x2-x1-0.8)}" height="${H-pad.b-Y(v)}"
             fill="${s.color}" opacity="${s.opacity??0.75}"${opts.overlay?` stroke="${s.color}"`:''}/>`; });
  });
  out+=`<text x="${pad.l}" y="11" font-size="10" fill="#666">${series.map(s=>`<tspan fill="${s.color}">■ ${s.name}</tspan>`).join('   ')}</text>`;
  out+=`<text x="${W/2}" y="${H-2}" font-size="10" fill="#888" text-anchor="middle">粒径 d (μm, log10)</text>`;
  return `<svg viewBox="0 0 ${W} ${H}">${out}</svg>`;
}

const COLORS=['#2f6fb0','#c0392b','#2e8b57','#b8860b','#7d4f9f','#3a8f9c'];
function render(r){
  const sample = SAMPLES.find(s=>s.id===r.sampleId) || {instruments:[]};
  // 原始箱：每台仪器一张
  $('rawCharts').innerHTML = (sample.instruments||[]).map(inst=>{
    const b=[...inst.edges[0]<=0?inst.edges:inst.edges];
    return `<div style="margin-bottom:6px"><b>${inst.instrument}</b> ${tag(inst.basis)}
      <span class="pill">箱 ${inst.values.length}</span>
      <span class="pill">首端点 ${fmt(inst.edges[0],4)}</span>
      <span class="pill">末箱右端点 ${fmt(inst.edges[inst.edges.length-1],3)}</span>
      <span class="pill">总量 ${fmt(inst.values.reduce((a,b)=>a+b,0),3)}</span>
      ${barChart([{name:inst.instrument,data:inst.values,color:inst.basis==='MASS'?'#c0392b':'#2f6fb0'}], inst.edges)}</div>`;
  }).join('');

  const cv=r.conversion;
  $('assumptions').innerHTML = `每次求解固定：网格 [${r.grid.minD}, ${r.grid.maxD}] μm，${r.grid.binsPerDecade} 箱/十倍程，共 ${r.gridEdges.length-1} 箱；
    目标口径 <b>${ {MASS:'质量',VOLUME:'体积',AREA:'面积',NUMBER:'数量'}[cv.toBasis] }</b>，
    密度 ρ=${cv.density} g/cm³，折射率 n=${cv.refractiveIndex}（激光光学假设记录，不做二次 Mie 修正）；
    转换模型 ${cv.modelName}（对数箱内均匀 + 等球径矩）；仪器合并 ${r.blendMode}。`;

  // 转换后分布 + 拟合
  const instSeries = r.instruments.map((iv,i)=>({name:iv.instrument+' 投影', data:iv.bins, color:COLORS[i+1], opacity:0.55}));
  $('convCharts').innerHTML = barChart(
    [...instSeries, {name:'合并观测',data:r.blended,color:'#222',opacity:0.9},
      {name:'首位候选拟合',data:r.fitted,color:'#2e8b57',opacity:0.5}],
    r.gridEdges, {overlay:true});

  // 守恒
  const rows = r.instruments.map(iv=>{
    const srcCons=iv.totalSource, on=iv.totalOnGridSource, lb=iv.outsideBelowSource, ub=iv.outsideAboveSource;
    const err=Math.abs(srcCons-on-lb-ub);
    return `<tr><td>${iv.instrument} (${ {MASS:'质量',VOLUME:'体积',AREA:'面积',NUMBER:'数量'}[iv.sourceBasis] }→${ {MASS:'质量',VOLUME:'体积',AREA:'面积',NUMBER:'数量'}[iv.gridBasis] })</td>
      <td>${fmt(srcCons)}</td><td>${fmt(on)}</td><td class="bad">${fmt(lb)}</td><td class="bad">${fmt(ub)}</td>
      <td>${fmt(iv.totalGridBasis)}</td><td class="${err>1e-8?'bad':'muted'}">${fmt(err,2)}</td></tr>`;
  }).join('');
  $('conservation').innerHTML = `
    <p style="margin:8px 0 4px">总量守恒（源口径，不归一化失量）：</p>
    <table><tr><th>仪器</th><th>源总量</th><th>网格内</th><th>网格下失量</th><th>网格上失量</th><th>目标口径总量</th><th>守恒差</th></tr>${rows}</table>
    <p class="muted">合并后网格内总量 <b>${fmt(r.blendedTotal)}</b>；覆盖箱 ${r.coverageCount}/${r.blended.length}；
    网格外未覆盖量（目标口径，按 ${r.blendMode} 记账）<b class="bad">${fmt(r.outsideTotalGridBasis)}</b>；
    各仪器目标口径总量之和 ${fmt(r.observedTotal)}。失量不参与反演行。</p>`;

  // 诊断
  const d=r.diagnostics;
  const pairs=d.nearCollinearPairs.map(p=>`<div>⚠ <code>${p.a}</code> ↔ <code>${p.b}</code>：
    Pearson r=${fmt(p.r,4)}，余弦=${fmt(p.cosine,4)}（近线性相关）</div>`).join('');
  $('diag').innerHTML = `<div class="${d.identifiable?'muted':'warn'}">${d.note}</div>
    <p class="muted">条件数≈${fmt(d.conditionNumber,2)}；最小列间|cos|=${fmt(d.minPairCosine,4)}；
      前8候选比例展布=${fmt(d.profileSpread,4)}；首二候选加权RSS差=${isFinite(d.topGap)?fmt(d.topGap,3):'∞'}</p>
    ${pairs||'<p class="muted">无近线性相关端元对。</p>'}`;

  // 解簇
  $('clusters').innerHTML = '<p><b>候选解簇</b>（比例欧氏距离 ≤0.08）：</p><table><tr><th>簇</th><th>大小</th><th>均值比例（'+r.endmemberIds.join(', ')+'）</th><th>代表候选</th></tr>' +
    r.clusters.map(c=>`<tr><td>#${c.id}</td><td>${c.size}</td>
      <td style="text-align:left;font-family:Menlo,monospace">[${c.meanFractions.map(v=>fmt(v,3)).join(', ')}]</td>
      <td>#${c.representativeRank}</td></tr>`).join('') + '</table>';

  // 候选表 + 比例条
  $('candidates').innerHTML = '<p><b>稀疏候选</b>（按 BIC 排序，支持集标灰）：</p>' +
    `<table><tr><th>#</th><th>簇</th>${r.endmemberIds.map((id,i)=>`<th>${id}<br><span class="muted">${(EMS.find(e=>e.id===id)||{name:''}).name}</span></th>`).join('')}
      <th>支持数</th><th>RSS</th><th>加权RSS</th><th>BIC</th></tr>` +
    r.candidates.map(c=>{
      const mx=Math.max(...c.fractions,1e-9);
      return `<tr><td>${c.rank}</td><td>#${c.clusterId}</td>` +
        c.fractions.map((f,i)=>{
          const active=c.support.includes(i);
          return `<td><div style="display:flex;align-items:center;gap:4px;justify-content:flex-end">
            <span class="${active?'':'muted'}" style="width:48px">${fmt(f,3)}</span>
            <span class="candbar" style="width:${Math.max(1,f/mx*70)}px;background:${active?COLORS[i%COLORS.length]:'#d8dce0'}"></span></div></td>`;
        }).join('') +
        `<td>${c.support.size}</td><td>${fmt(c.rss,3)}</td><td>${fmt(c.weightedRss,3)}</td><td>${fmt(c.bic,2)}</td></tr>`;
    }).join('') + '</table>';

  // 残差图
  const zero=r.residuals.map(()=>0);
  $('residual').innerHTML = barChart(
    [{name:'残差(观测-拟合)',data:r.residuals,color:'#c0392b',opacity:0.85},
     {name:'0',data:zero,color:'#999',opacity:0.2}], r.gridEdges, {overlay:true})
    + barChart([{name:'白化加权残差',data:r.weightedResiduals,color:'#b8860b',opacity:0.85}], r.gridEdges, {overlay:true});
}
refresh();
