import * as THREE from "three";
import {COASTLINE,ISLAND_CENTER} from "./island";
import {LANDSCAPES,coastalWeight} from "./landscapes";
import {nearestRiver} from "./river";
import {OCEAN_LEVEL,scaledCoastline} from "./river-outlet";

const smooth=(a:number,b:number,value:number)=>{const t=THREE.MathUtils.clamp((value-a)/(b-a),0,1);return t*t*(3-2*t);};

/** A narrow ribbon at the actual sand/water edge, never a filled island polygon. */
export function createCoastalSurfGeometry() {
  const edge=scaledCoastline(1.012),positions:number[]=[],surf:number[]=[],indices:number[]=[];
  // Extra cross-shore samples resolve the shallow run-up and rounded wave crests.
  const bands=[-3.4,-2.5,-1.6,-.8,0,.6,1.2,2,3,4,5,6,7,8,9,10,12,14,16,19,23,28,35,44];
  const corners=edge.map((point,index)=>{
    const before=edge[(index+edge.length-1)%edge.length],after=edge[(index+1)%edge.length];
    const normal=(a:typeof point,b:typeof point)=>{
      const dx=b.x-a.x,dz=b.z-a.z,length=Math.hypot(dx,dz);
      return {x:dz/length,z:-dx/length};
    };
    const a=normal(before,point),b=normal(point,after),length=Math.hypot(a.x+b.x,a.z+b.z);
    let x=(a.x+b.x)/length,z=(a.z+b.z)/length;
    if(x*(point.x-ISLAND_CENTER.x)+z*(point.z-ISLAND_CENTER.z)<0){x=-x;z=-z;}
    const miter=1/Math.max(.5,Math.abs(x*b.x+z*b.z));
    return {x:x*miter,z:z*miter};
  });
  for(let segment=0;segment<edge.length;segment++) {
    const a=edge[segment],b=edge[(segment+1)%edge.length],na=corners[segment],nb=corners[(segment+1)%edge.length];
    const steps=Math.ceil(Math.hypot(b.x-a.x,b.z-a.z)/2),start=positions.length/3,open:boolean[]=[];
    for(let step=0;step<=steps;step++) {
      const t=step/steps,x=a.x+(b.x-a.x)*t,z=a.z+(b.z-a.z)*t;
      const nx=na.x+(nb.x-na.x)*t,nz=na.z+(nb.z-na.z)*t,length=Math.hypot(nx,nz);
      const coast=COASTLINE[segment],next=COASTLINE[(segment+1)%COASTLINE.length];
      const cx=coast.x+(next.x-coast.x)*t,cz=coast.z+(next.z-coast.z)*t;
      let exposure=.85;
      for(const zone of LANDSCAPES.coastalZones)exposure+=((zone.kind==="cove"?.65:1.15)-exposure)*coastalWeight(zone,cx,cz);
      // Do not lay a wash sheet or wet sand across the river's open channel.
      for(const distance of bands) {
        const px=x+nx*distance,pz=z+nz*distance,river=nearestRiver(px,pz);
        open.push(river.distance>river.width/2+2.5);
        positions.push(px,OCEAN_LEVEL+.035+.46*(1-smooth(0,2,distance)),pz);
        surf.push(distance,nx/length,nz/length,exposure);
      }
    }
    for(let step=0;step<steps;step++)for(let band=0;band<bands.length-1;band++) {
      const local=step*bands.length+band,a=start+local,b=a+bands.length;
      if([local,local+1,local+bands.length,local+bands.length+1].every(i=>open[i]))indices.push(a,b,a+1,b,b+1,a+1);
    }
  }
  const geometry=new THREE.BufferGeometry();
  geometry.setAttribute("position",new THREE.Float32BufferAttribute(positions,3));
  geometry.setAttribute("surf",new THREE.Float32BufferAttribute(surf,4));
  geometry.setIndex(indices);geometry.computeVertexNormals();geometry.computeBoundingSphere();
  // Vertex displacement is bounded, but still participates in frustum culling.
  geometry.boundingSphere!.radius+=.5;
  return geometry;
}

export const marineUniforms=()=>({
  uSurfTime:{value:0},uSurfRain:{value:0},
  uSurfWind:{value:new THREE.Vector2(1,0)},uSurfWindStrength:{value:0},
  uSurfPhase:{value:new THREE.Vector4()},uSurfWashPhase:{value:0},uSurfNoise:{value:new THREE.Vector4()},
  uFoamColor:{value:new THREE.Color("#edf5eb")},
  uWashColor:{value:new THREE.Color("#8dbeb5")},
  uWetSandColor:{value:new THREE.Color("#a69d7d")},
});

const cycle=(value:number)=>((value%(Math.PI*2))+Math.PI*2)%(Math.PI*2);
/** Reduce phases in double precision before uploading floats to a long-running world. */
export function updateMarineUniforms(uniforms:ReturnType<typeof marineUniforms>,frame:{seconds:number;wind:{x:number;z:number;strength:number}}) {
  const seconds=Number.isFinite(frame.seconds)?frame.seconds:0;
  uniforms.uSurfTime.value=seconds;
  uniforms.uSurfWind.value.set(frame.wind.x,frame.wind.z);uniforms.uSurfWindStrength.value=frame.wind.strength;
  uniforms.uSurfPhase.value.set(cycle(seconds*.91851),cycle(seconds*.19),cycle(seconds*.46456),cycle(seconds*.62642));
  uniforms.uSurfWashPhase.value=cycle(seconds*.69);
  // Bounded turbulent texture coordinates prevent loss of sub-frame precision
  // without introducing a visible modulo seam across the water surface.
  uniforms.uSurfNoise.value.set(Math.sin(cycle(seconds*.0012))*10,Math.sin(cycle(seconds*.007))*20,Math.cos(cycle(seconds*.005))*20,Math.sin(cycle(seconds*.0035))*10);
}

// Analytic, world-space ripples need no textures, render targets or per-frame
// geometry uploads. Standard-material lighting keeps the sea dark at night.
const waterFunctions=/* glsl */`
  uniform float uSurfTime;
  uniform float uSurfRain;
  uniform vec2 uSurfWind;
  uniform float uSurfWindStrength;
  uniform vec4 uSurfPhase;
  uniform float uSurfWashPhase;
  uniform vec4 uSurfNoise;
  varying vec2 vMarinePosition;
  float seaNoise(vec2 p) {
    vec2 cell=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);
    vec4 h=fract(sin(vec4(dot(cell,vec2(127.1,311.7)),dot(cell+vec2(1.,0.),vec2(127.1,311.7)),dot(cell+vec2(0.,1.),vec2(127.1,311.7)),dot(cell+vec2(1.),vec2(127.1,311.7))))*43758.5453);
    return mix(mix(h.x,h.y,f.x),mix(h.z,h.w,f.x),f.y);
  }
  float shorePhase(vec2 p,float distance) {
    return distance*.86+uSurfPhase.x+sin(dot(p,vec2(.021,.013)))*.9+sin(dot(p,vec2(-.043,.027))-uSurfPhase.y)*.32;
  }
`;

export function marineShader(uniforms:ReturnType<typeof marineUniforms>,shore=false):THREE.MeshStandardMaterial["onBeforeCompile"] {
  return shader=>{
    Object.assign(shader.uniforms,uniforms);
    shader.vertexShader=waterFunctions+(shore?"attribute vec4 surf; varying vec4 vSurf;\n":"")+shader.vertexShader;
    shader.vertexShader=shader.vertexShader.replace("#include <begin_vertex>",/* glsl */`
      #include <begin_vertex>
      vMarinePosition=(modelMatrix*vec4(position,1.0)).xz;
      ${shore?`vSurf=surf;
      float crest=pow(.5+.5*sin(shorePhase(vMarinePosition,surf.x)),4.0);
      transformed.y+=crest*(.13+.07*uSurfRain)*(.65+.5*uSurfWindStrength)*surf.w*smoothstep(0.,3.,surf.x)*(1.-smoothstep(23.,44.,surf.x));`:""}
    `);
    shader.fragmentShader=waterFunctions+(shore?/* glsl */`
      varying vec4 vSurf;
      uniform vec3 uFoamColor;
      uniform vec3 uWashColor;
      uniform vec3 uWetSandColor;
    `:"")+shader.fragmentShader;
    shader.fragmentShader=shader.fragmentShader.replace("#include <color_fragment>",/* glsl */`
      #include <color_fragment>
      float detail=1./(1.+length(fwidth(vMarinePosition))*.5);
      float wind=seaNoise(vMarinePosition*.035+vec2(uSurfNoise.x,0.));
      float warp=seaNoise(vMarinePosition*.055)*5.;
      vec2 windDirection=normalize(uSurfWind+vec2(.000001,0.));
      vec2 rippleDirection=vec2(windDirection.x*.87758-windDirection.y*.47943,windDirection.x*.47943+windDirection.y*.87758);
      // Deep-water dispersion omega=sqrt(g*k), in the island's ten-metre units.
      float swellPhase=dot(vMarinePosition,windDirection*.22)-uSurfPhase.z+warp;
      float ripplePhase=dot(vMarinePosition,rippleDirection*.4)-uSurfPhase.w+warp*1.7;
      float windEnergy=.55+.65*uSurfWindStrength;
      diffuseColor.rgb*=1.+(sin(swellPhase)*.018+sin(ripplePhase)*.008)*detail*(.3+.7*wind)*windEnergy;
      ${shore?`
      float d=vSurf.x;
      float phase=shorePhase(vMarinePosition,d);
      float grain=seaNoise(vMarinePosition*1.65+uSurfNoise.yz);
      float lace=seaNoise(vMarinePosition*3.8+grain);
      float breakup=seaNoise(vMarinePosition*.12+vec2(uSurfNoise.w,0.));
      float front=pow(.5+.5*sin(phase+grain*.45),12.);
      float breaker=front*smoothstep(.4,2.,d)*(1.-smoothstep(14.,28.,d));
      breaker*=smoothstep(.2,.55,breakup)*(.5+.5*lace)*vSurf.w;
      float runup=-1.0+sin(uSurfWashPhase+sin(dot(vMarinePosition,vec2(.023,.017)))*1.2)*1.5;
      float wash=smoothstep(runup-.35,runup+.55,d)*(1.-smoothstep(1.,4.,d));
      float washEdge=(1.-smoothstep(.12,.65,abs(d-runup+grain*.3)))*(.45+.55*lace);
      float remnant=(1.-smoothstep(-2.5,.8,d))*(.12+.13*grain);
      float foam=clamp(breaker*.85+washEdge*.75+wash*front*.18,0.,.94);
      vec3 water=mix(uWashColor,diffuseColor.rgb,smoothstep(2.,22.,d));
      water=mix(water,vec3(.23,.37,.39),uSurfRain*.2);
      diffuseColor.rgb=mix(mix(uWetSandColor,water,smoothstep(-.5,1.2,d)+wash*(1.-smoothstep(-.5,1.2,d))),uFoamColor,foam);
      float waterAlpha=smoothstep(0.,2.,d)*.3;
      diffuseColor.a=(max(remnant,waterAlpha)+wash*.24+foam*.7)*smoothstep(-3.4,-2.5,d)*(1.-smoothstep(28.,44.,d));
      if(diffuseColor.a<.003)discard;
      `:""}
    `);
    shader.fragmentShader=shader.fragmentShader.replace("#include <normal_fragment_begin>",/* glsl */`
      #include <normal_fragment_begin>
      vec2 slope=(windDirection*.22*cos(swellPhase)*.11
        +rippleDirection*.4*cos(ripplePhase)*.04)*detail*(.3+.7*wind)*windEnergy;
      ${shore?`slope+=vSurf.yz*cos(shorePhase(vMarinePosition,vSurf.x))*.08*smoothstep(0.,3.,vSurf.x);`:""}
      normal=normalize(normal+mat3(viewMatrix)*vec3(-slope.x,0.,-slope.y)*(1.+uSurfRain*.5));
    `);
  };
}
