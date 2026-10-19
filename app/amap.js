/* Coordinate conversion adapted from BSD-2-Clause eviltransform (bundled license). */
(function(){
'use strict';
function validPoint(p){return Array.isArray(p)&&p.length===2&&p.every(Number.isFinite)&&Math.abs(p[0])<=85&&Math.abs(p[1])<=180}
function outside(lat,lon){return !(lon>=72.004&&lon<=137.8347&&lat>=.8293&&lat<=55.8271)}
function forward(lat,lon){
 if(outside(lat,lon))return [lat,lon];
 const x=lon-105,y=lat-35,pi=Math.PI,a=Math.sqrt(Math.abs(x));
 let d=20*Math.sin(6*x*pi)+20*Math.sin(2*x*pi);
 let dy=(d+20*Math.sin(y*pi)+40*Math.sin(y*pi/3)+160*Math.sin(y*pi/12)+320*Math.sin(y*pi/30))*2/3-100+2*x+3*y+.2*y*y+.1*x*y+.2*a;
 let dx=(d+20*Math.sin(x*pi)+40*Math.sin(x*pi/3)+150*Math.sin(x*pi/12)+300*Math.sin(x*pi/30))*2/3+300+x+2*y+.1*x*x+.1*x*y+.1*a;
 const r=lat*pi/180,ee=.00669342162296594323,m=1-ee*Math.sin(r)**2,s=Math.sqrt(m),earth=6378137;
 return [lat+dy*180/(earth*(1-ee)/(m*s)*pi),lon+dx*180/(earth/s*Math.cos(r)*pi)];
}
function inverse(lat,lon){
 if(outside(lat,lon))return [lat,lon];
 let lo=[lat-.01,lon-.01],hi=[lat+.01,lon+.01],p;
 for(let i=0;i<40;i++){p=[(lo[0]+hi[0])/2,(lo[1]+hi[1])/2];const q=forward(...p),delta=[q[0]-lat,q[1]-lon];if(Math.max(...delta.map(Math.abs))<1e-10)return p;for(let j=0;j<2;j++)if(delta[j]>0)hi[j]=p[j];else lo[j]=p[j];}
 return p;
}
function segments(path){const result=[];let current=[];for(const point of path){if(validPoint(point)){current.push(point)}else if(current.length){result.push(current);current=[]}}if(current.length)result.push(current);return result}
if(typeof module!=='undefined')module.exports={forward,inverse,validPoint,segments};
if(typeof document==='undefined')return;
window.__AMAP_PAGE=true;
let map=null,pin=null,range=null,target=null,paths=[[],[],[]],fresh=false,overlays=[],received=null,loading=true;
const hint=document.getElementById('hint');
function lnglat(point){const p=forward(...point);return [p[1],p[0]]}
function bridge(name,value){window.webkit?.messageHandlers[name]?.postMessage(value)}
for(const level of ['error','warn']){const original=console[level].bind(console);console[level]=(...args)=>{bridge('mapDiagnostic',level+' '+args.map(String).join(' '));original(...args)}}
window.addEventListener?.('error',event=>bridge('mapDiagnostic',event.message||'script-error'));
window.addEventListener?.('unhandledrejection',event=>bridge('mapDiagnostic','promise '+String(event.reason)));
window.addEventListener?.('securitypolicyviolation',event=>bridge('mapDiagnostic','CSP '+event.violatedDirective+' '+String(event.blockedURI).split('?')[0]));
function fallback(reason){loading=false;hint.textContent=reason;bridge('mapFallback',reason)}
function pick(point){const wgs=inverse(point.getLat(),point.getLng());if(validPoint(wgs))bridge('pickPlace',wgs)}
function draw(){
 if(!map)return;
 if(!range){range=new AMap.Circle({center:lnglat(target||[38.89761,-77.03637]),radius:150,fillColor:'#2196f3',fillOpacity:.2,strokeColor:'#1976d2',strokeWeight:2,zIndex:10});map.add(range)}
 else range.setCenter(lnglat(target||[38.89761,-77.03637]));
 map.remove(overlays);overlays=[];
 paths.forEach((path,index)=>{for(const part of segments(path)){if(part.length<2)continue;const line=new AMap.Polyline({path:part.map(lnglat),strokeColor:['#64748b','#e28624','#2563eb'][index],strokeWeight:index===2?3:2,strokeStyle:index===0?'dashed':'solid',zIndex:30+index});map.add(line);overlays.push(line)}});
 const last=paths[2].filter(validPoint).at(-1);
 if(received){map.remove(received);received=null}
 if(last){received=new AMap.CircleMarker({center:lnglat(last),radius:5,fillColor:fresh?'#2563eb':'#64748b',fillOpacity:1,strokeColor:'#fff',strokeWeight:2,zIndex:60});map.add(received)}
 document.getElementById('receiver').hidden=!last;
}
window.setLocation=function(lat,lon){if(!validPoint([lat,lon]))return false;target=[lat,lon];if(map){pin.setPosition(lnglat(target));map.setCenter(lnglat(target));draw()}return true};
window.setTrajectories=function(ideal,emitted,actual,isFresh){paths=[ideal,emitted,actual].map(p=>Array.isArray(p)?p.slice(-3600):[]);fresh=isFresh===true;draw()};
window.setSimulation=function(){};
document.getElementById('minus').onclick=()=>map?.zoomOut();document.getElementById('plus').onclick=()=>map?.zoomIn();
document.getElementById('home').onclick=()=>{if(map&&target)map.setCenter(lnglat(target))};
document.getElementById('receiver').onclick=()=>{const p=paths[2].filter(validPoint).at(-1);if(map&&p)map.setCenter(lnglat(p))};
document.getElementById('offline').onclick=()=>fallback('已切换离线网格；可点击高德返回');
function initialize(){
 bridge('mapDiagnostic','sdk-loaded');
 try{
  map=new AMap.Map('map',{zoom:14,zooms:[3,18],viewMode:'2D',doubleClickZoom:false,center:lnglat(target||[38.89761,-77.03637])});
  pin=new AMap.Marker({position:lnglat(target||[38.89761,-77.03637]),draggable:true,content:'<div class="pin"></div>',anchor:'center',zIndex:70});map.add(pin);
  map.on('dblclick',event=>pick(event.lnglat));pin.on('dragend',event=>pick(event.lnglat));
  map.on('complete',()=>{loading=false;bridge('mapDiagnostic','map-complete');hint.textContent='高德地图 · GCJ-02 底图 · 模拟参数保持 WGS-84'});
  map.on('error',()=>fallback('高德地图加载失败，已保留离线网格'));
  bridge('mapDiagnostic','container '+document.getElementById('map').clientWidth+'x'+document.getElementById('map').clientHeight+' visibility '+document.visibilityState);
  draw();
 }catch(error){bridge('mapDiagnostic',String(error));fallback('高德地图初始化失败，已保留离线网格')}
}
if(!window.__AMAP_KEY){fallback('未配置高德地图，使用离线网格');return}
bridge('mapDiagnostic','sdk-requested');
const script=document.createElement('script');script.src='https://webapi.amap.com/maps?v=2.0&key='+encodeURIComponent(window.__AMAP_KEY);script.onload=initialize;script.onerror=()=>fallback('高德服务无法连接，使用离线网格');document.head.append(script);
// Background WKWebViews may suspend drawing; only time visible loading.
let visibleWait=0;
function checkLoading(){if(!loading)return;if(document.visibilityState!=='hidden')visibleWait+=1000;if(visibleWait>=15000)fallback('高德地图加载超时，已保留离线网格');else setTimeout(checkLoading,1000)}
setTimeout(checkLoading,1000);
})();
