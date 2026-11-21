"use client";
import {useMemo,useRef} from "react";
import {useFrame} from "@react-three/fiber";
import * as THREE from "three";
import {skyAt} from "@/lib/sky";
import {readStormFrame} from "@/lib/storm";
import {useAtmosphericMotion} from "./AtmosphericMotion";

// One background draw, no textures, point sprites, extra lights or sky geometry
// at world scale. Reconstructing a ray keeps the sky infinitely distant.
const vertexShader=`varying vec2 vUv;
void main(){vUv=uv;gl_Position=vec4(position.xy,1.0,1.0);}`;
const fragmentShader=`
varying vec2 vUv;
uniform mat4 inverseProjection, cameraWorld;
uniform vec3 sunDirection, moonA, moonB, zenith, horizon;
uniform float daylight, cloud, starVisibility, moonVisibility, overview, stormFlash;
uniform vec2 cloudOffset;
float hash(vec3 p){p=fract(p*.1031);p+=dot(p,p.yzx+33.33);return fract((p.x+p.y)*p.z);}
float noise(vec3 p){vec3 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);
  return mix(mix(mix(hash(i),hash(i+vec3(1,0,0)),f.x),mix(hash(i+vec3(0,1,0)),hash(i+vec3(1,1,0)),f.x),f.y),
  mix(mix(hash(i+vec3(0,0,1)),hash(i+vec3(1,0,1)),f.x),mix(hash(i+vec3(0,1,1)),hash(i+vec3(1,1,1)),f.x),f.y),f.z);}
vec3 moon(vec3 sky,vec3 ray,vec3 center,float radius,vec3 tint){
  vec3 offset=ray-center*dot(ray,center);
  float r=length(offset)/radius;
  if(moonVisibility<=0.0||r>1.02||dot(ray,center)<0.0)return sky;
  vec3 normal=offset/radius-center*sqrt(max(0.0,1.0-r*r));
  float light=max(0.0,dot(normal,sunDirection));
  float craters=.78+.18*noise(normal*21.0)+.12*noise(normal*47.0);
  vec3 surface=tint*(.035+light*.95)*craters;
  surface=mix(surface,horizon,daylight*.35);
  return mix(sky,surface,moonVisibility*(1.0-smoothstep(.97,1.02,r))*smoothstep(-.01,.035,ray.y));
}
void main(){
  vec4 view=inverseProjection*vec4(vUv*2.0-1.0,1.0,1.0);
  vec3 ray=normalize((cameraWorld*vec4(view.xyz,0.0)).xyz);
  float height=max(0.0,ray.y);
  vec3 sky=mix(horizon,zenith,pow(height,.38));
  float sunDot=dot(ray,sunDirection);
  float golden=(1.0-smoothstep(.05,.4,abs(sunDirection.y)))*smoothstep(-.22,.02,sunDirection.y);
  sky+=vec3(.38,.13,.025)*pow(max(sunDot,0.0),8.0)*golden;
  if(overview<.5){
    // Fixed spherical cells: stars cannot reroll when moving or reconnecting.
    vec2 grid=vec2(atan(ray.z,ray.x)/6.2831853+.5,asin(ray.y)/3.1415927+.5)*vec2(320.0,160.0);
    vec3 cell=vec3(floor(grid),17.0);
    vec2 center=vec2(hash(cell+2.0),hash(cell+7.0))*.6+.2;
    vec2 coordinate=(floor(grid)+center)/vec2(320.0,160.0);
    float azimuth=(coordinate.x-.5)*6.2831853,latitude=(coordinate.y-.5)*3.1415927;
    vec3 starRay=vec3(cos(latitude)*cos(azimuth),sin(latitude),cos(latitude)*sin(azimuth));
    float radius=mix(.00065,.00115,hash(cell+29.0)),aa=length(fwidth(ray))*.5;
    // Measure in direction space so stars stay round near the celestial poles.
    float chance=.024*sqrt(max(0.0,1.0-ray.y*ray.y));
    float star=(1.0-smoothstep(radius-aa,radius+aa,length(ray-starRay)))*step(1.0-chance,hash(cell));
    sky+=vec3(.72,.83,1.0)*star*starVisibility*smoothstep(.01,.15,height)*(.2+hash(cell+40.0)*.8);
    sky=moon(sky,ray,moonA,.032,vec3(.82,.89,1.0));
    sky=moon(sky,ray,moonB,.020,vec3(1.0,.70,.43));
    float sunDisc=smoothstep(cos(.018),cos(.015),sunDot)*smoothstep(-.015,.02,ray.y);
    sky+=vec3(1.0,.78,.42)*(sunDisc*2.5+pow(max(sunDot,0.0),180.0)*.15)*smoothstep(-.05,.01,sunDirection.y);
  }
  // Intersect a distant cloud layer. Moving the sampling plane downwind gives
  // continuous transport and camera parallax without moving the celestial sky.
  vec2 cloudPlane=cameraWorld[3].xz+ray.xz*180.0/max(.12,ray.y)-cloudOffset;
  vec3 cloudPoint=vec3(cloudPlane.x*.008,.7,cloudPlane.y*.008);
  float texture=noise(cloudPoint)*.65+noise(cloudPoint*2.7)*.35;
  float cover=smoothstep(1.0-cloud*.85,1.1-cloud*.6,texture);
  cover=max(cover,cloud>.9?.82:cloud*.28);
  vec3 cloudColor=mix(vec3(.045,.063,.095),vec3(.48,.56,.61),daylight);
  sky=mix(sky,cloudColor,cover*smoothstep(-.1,.12,ray.y));
  if(overview>.5)sky=mix(horizon,zenith,vUv.y*.35);
  sky=mix(sky,vec3(.48,.58,.73),stormFlash*.26);
  gl_FragColor=vec4(sky,1.0);
  #include <tonemapping_fragment>
  #include <colorspace_fragment>
}`;

export default function CelestialSky({minute,weather,reducedMotion}:{minute:number;weather:string;reducedMotion:boolean}) {
  const sky=useMemo(()=>skyAt(minute,weather),[minute,weather]);
  const motion=useAtmosphericMotion();
  const material=useRef<THREE.ShaderMaterial>(null);
  const palette=useMemo(()=>({day:new THREE.Color("#578faf"),horizon:new THREE.Color("#d2e0df"),gold:new THREE.Color("#efa76f")}),[]);
  const uniforms=useMemo(()=>({inverseProjection:{value:new THREE.Matrix4()},cameraWorld:{value:new THREE.Matrix4()},
    sunDirection:{value:new THREE.Vector3()},moonA:{value:new THREE.Vector3()},moonB:{value:new THREE.Vector3()},
    zenith:{value:new THREE.Color()},horizon:{value:new THREE.Color()},daylight:{value:0},cloud:{value:0},starVisibility:{value:0},moonVisibility:{value:0},cloudOffset:{value:new THREE.Vector2()},overview:{value:0},stormFlash:{value:0}}),[]);
  useFrame(({camera,gl})=>{
    // R3F copies the uniform wrappers on assignment. Update the material's live
    // values: mutating the original scalar wrappers leaves the GPU at zero.
    const live=material.current?.uniforms as typeof uniforms|undefined;if(!live)return;
    live.inverseProjection.value.copy(camera.projectionMatrixInverse);live.cameraWorld.value.copy(camera.matrixWorld);
    live.sunDirection.value.fromArray(sky.sunDirection);live.moonA.value.fromArray(sky.moons[0].direction);live.moonB.value.fromArray(sky.moons[1].direction);
    live.zenith.value.set("#101b36").lerp(palette.day,sky.daylight);
    live.horizon.value.set("#293751").lerp(palette.horizon,sky.daylight).lerp(palette.gold,sky.twilight*.68);
    live.daylight.value=sky.daylight;live.cloud.value=sky.cloud;live.starVisibility.value=sky.stars;live.moonVisibility.value=sky.moonVisibility;
    live.cloudOffset.value.set(motion.current.advection.x*1.5,motion.current.advection.z*1.5);live.overview.value=camera instanceof THREE.OrthographicCamera?1:0;
    live.stormFlash.value=reducedMotion?0:readStormFrame(gl.domElement)?.flash??0;
    Object.assign(gl.domElement.dataset,{skyMinute:String(minute),sunDirection:sky.sunDirection.map(v=>v.toFixed(5)).join(","),
      moonSelene:sky.moons[0].direction.map(v=>v.toFixed(5)).join(","),moonEmber:sky.moons[1].direction.map(v=>v.toFixed(5)).join(","),
      moonPhases:sky.moons.map(m=>m.illuminated.toFixed(4)).join(","),moonsAboveHorizon:String(sky.moons.filter(m=>m.aboveHorizon).length),moonVisibility:sky.moonVisibility.toFixed(3),stars:sky.stars.toFixed(3),cloudOffset:live.cloudOffset.value.toArray().map(v=>v.toFixed(4)).join(","),cloudWind:[motion.current.wind.x,motion.current.wind.z].map(v=>v.toFixed(4)).join(",")});
  });
  return <mesh name="shared-sun-and-two-moon-sky" frustumCulled={false} renderOrder={-1000} raycast={()=>null}>
    <planeGeometry args={[2,2]}/><shaderMaterial ref={material} uniforms={uniforms} vertexShader={vertexShader} fragmentShader={fragmentShader} depthWrite={false} depthTest={false}/>
  </mesh>;
}
