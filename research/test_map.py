"""Map geometry tests without requesting any network tiles."""
from pathlib import Path
import re,subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parent.parent
class MapTest(unittest.TestCase):
    def test_projection_and_script(self):
        html=(ROOT/'shared/map.html').read_text();script=re.search(r'<script>(.*)</script>',html,re.S)[1]
        with tempfile.TemporaryDirectory() as tmp:
            js=Path(tmp)/'map.js';js.write_text(script)
            subprocess.run(['node','--check',str(js)],check=True)
            geometry='let z=16;\n'+'\n'.join(re.findall(r'^function (?:project|unproject).*$',script,re.M))
            geometry+='''
for(z=3;z<=18;z++)for(const [lat,lon] of [[39.9,116.4],[0,0],[-33.86,151.2],[60,179.9],[-60,-179.9]]){
 const [x,y]=project(lat,lon),[a,b]=unproject(x,y);
 if(Math.abs(lat-a)>1e-8||Math.abs(lon-b)>1e-8)throw Error('Projection mismatch');
}
'''
            js.write_text(geometry);subprocess.run(['node',str(js)],check=True)
        self.assertIn('© <a href="https://www.openstreetmap.org/copyright">',html)
        self.assertNotIn('geolocation',script)
        self.assertIn('let z=15,center',script)
    def test_default_view_is_white_house_without_target(self):
        script=re.search(r'<script>(.*)</script>',(ROOT/'shared/map.html').read_text(),re.S)[1]
        harness="""
const controls={};
const document={getElementById(id){return controls[id]??=( {style:{},clientWidth:0,clientHeight:0,replaceChildren(){}} );}};
const window={};class ResizeObserver{constructor(callback){this.callback=callback}observe(){this.callback()}}
"""
        check="""
if(center[0]!==38.89761||center[1]!==-77.03637||target!==null||pin.style.display!=='none')throw Error('Default view must not select a target');
window.setLocation(51.5,-.12);if(target[0]!==51.5)throw Error('Explicit selection lost');
window.clearLocation();if(target!==null||center[0]!==38.89761||center[1]!==-77.03637||pin.style.display!=='none')throw Error('Empty target must retain public view');
"""
        with tempfile.TemporaryDirectory() as tmp:
            js=Path(tmp)/'default-map.js';js.write_text(harness+script+check)
            subprocess.run(['node',str(js)],check=True)

    def test_received_tracks_preserve_gaps_and_freshness_colour(self):
        html=(ROOT/'shared/map.html').read_text();script=re.search(r'<script>(.*)</script>',html,re.S)[1]
        functions='\n'.join(re.findall(r'^function (?:project|validPoint).*$',script,re.M))
        functions+='\n'+re.search(r'function renderTracks\(.*?\n}',script,re.S)[0]
        check="""
let target=[30,104],z=18,trajectories=[[[30,104],[30,104.0001]],[],[[30,104],[],[30.001,104],[NaN,104],[30.002,104]]],receivedFresh=true;
const lines=[],fills=[];
const ctx={beginPath(){},moveTo(){},lineTo(x,y){lines.push([x,y])},stroke(){},setLineDash(){},arc(){},fill(){fills.push(this.fillStyle)}};
const document={getElementById(){return {getContext(){return ctx}}}};
if(validPoint([NaN,104])||validPoint([91,104])||!validPoint([30,104]))throw Error('Coordinate validation');
renderTracks(600,280,project(30,104),256*2**z);
if(lines.length!==1||fills.at(-1)!=='#2563eb')throw Error('Track gap or fresh marker');
receivedFresh=false;renderTracks(600,280,project(30,104),256*2**z);
if(fills.at(-1)!=='#64748b')throw Error('Stale marker');
"""
        with tempfile.TemporaryDirectory() as tmp:
            js=Path(tmp)/'tracks.js';js.write_text(functions+check)
            subprocess.run(['node',str(js)],check=True)

if __name__=='__main__':unittest.main()
