"""Mac AMap checks without credentials, network or RF."""
from pathlib import Path
import json,subprocess,unittest,re
import eviltransform
ROOT=Path(__file__).resolve().parent.parent
NODE='node'
class AMapTest(unittest.TestCase):
    def node(self,source):return subprocess.check_output([NODE,'-e',source],text=True)
    def test_coordinate_conversion(self):
        points=[[39.9,116.4],[39.9,116.4],[22.5,114],[0,0],[-33.86,151.2]]
        results=json.loads(self.node(f"const c=require({json.dumps(str(ROOT/'app/amap.js'))});console.log(JSON.stringify({json.dumps(points)}.map(p=>[c.forward(...p),c.inverse(...c.forward(...p))])));"))
        for point,(forward,inverse) in zip(points,results):
            for a,b in zip(forward,eviltransform.wgs2gcj(*point)):self.assertAlmostEqual(a,b,places=10)
            for a,b in zip(inverse,point):self.assertAlmostEqual(a,b,places=8)
    def test_gaps_and_invalid_points(self):
        self.node(f"const c=require({json.dumps(str(ROOT/'app/amap.js'))});if(c.validPoint([NaN,104])||c.validPoint([86,104])||c.validPoint([30,181]))throw Error('invalid accepted');if(c.segments([[30,104],[30.1,104],[],[30.2,104]]).map(p=>p.length).join(',')!=='2,1')throw Error('gap joined');")
    def test_sdk_bridge(self):
        harness=r'''
const vm=require('vm');let script,events={},messages=[],lines=[],timers=[];const controls={map:{clientWidth:600,clientHeight:330}};
for(const id of ['hint','minus','plus','home','receiver','offline'])controls[id]={};
const map={add(){},remove(){},setCenter(p){this.center=p},on(n,f){events[n]=f},zoomIn(){},zoomOut(){}};
const pin={setPosition(p){this.position=p},on(n,f){events['pin'+n]=f}};
const AMap={Map:function(id,options){if(options.zoom!==14)throw Error("Default zoom must be 14");return map},Marker:function(){return pin},Polyline:function(p){lines.push(p);return p},CircleMarker:function(p){return p},Circle:function(p){if(p.radius!==150)throw Error("Radius must be 150m");return {setCenter(){}}}};
const context={module:{exports:{}},window:{__AMAP_KEY:'test-key',webkit:{messageHandlers:{pickPlace:{postMessage(p){messages.push(['pick',p])}},mapFallback:{postMessage(p){messages.push(['fallback',p])}}}}},document:{visibilityState:'hidden',getElementById(id){return controls[id]},createElement(){return {}},head:{append(s){script=s}}},AMap,setTimeout(f){timers.push(f)}};
vm.createContext(context);vm.runInContext(SOURCE,context);script.onload();for(let i=0;i<20;i++)timers.shift()();if(messages.length)throw Error('hidden map timed out');context.document.visibilityState='visible';for(let i=0;i<14;i++)timers.shift()();if(messages.length)throw Error('visible timeout too soon');events.complete();
context.window.setLocation(39.9,116.4);
if(Math.abs(map.center[0]-116.4)<.001)throw Error('WGS plotted without conversion');
context.window.setTrajectories([[30,104],[30.1,104]],[],[[30,104],[],[30.1,104],[30.2,104]],true);
if(lines.length!==2||lines[0].strokeColor!=='#64748b'||lines[1].strokeColor!=='#2563eb')throw Error('gaps/colors');
const q=context.module.exports.forward(39.9,116.4);
events.dblclick({lnglat:{getLat(){return q[0]},getLng(){return q[1]}}});
if(Math.abs(messages[0][1][0]-39.9)>1e-8||Math.abs(messages[0][1][1]-116.4)>1e-8)throw Error('pick changed WGS target');
controls.offline.onclick();if(messages.at(-1)[0]!=='fallback')throw Error('offline bridge');
'''
        self.node(harness.replace('SOURCE',json.dumps((ROOT/'app/amap.js').read_text())))
    def test_no_embedded_credentials(self):
        for name in ['amap.js','amap.html']:self.assertIsNone(re.search(r'\b[a-f0-9]{32}\b',(ROOT/'app'/name).read_text()))
        self.assertIn('amap.json',(ROOT/'app/WalkLab.swift').read_text())
if __name__=='__main__':unittest.main()
