import * as THREE from "three";
import type {PlantKind} from "./living-environment";
import type {WindSample} from "./wind";

export const VEGETATION_MAX_BEND=.5;
type Root={x:number;y:number;z:number};
type Wind=Pick<WindSample,"x"|"z"|"strength">;
export type VegetationWindFrame={seconds:number;wind:Wind;reducedMotion:boolean};
const clamp=(value:number,limit=1)=>Math.max(0,Math.min(limit,Number.isFinite(value)?value:0));

/** Stable phases decorrelate nearby plants without starting another animation clock. */
export function vegetationPhase(id:number,x:number,z:number) {
  const value=Math.sin(id*127.1+x*.013+z*.017+311.7)*43758.5453;
  return (value-Math.floor(value))*Math.PI*2;
}

/** Maximum local bend in map units. Ground vertices remain fixed before slope conformance. */
export function vegetationFlex(kind:PlantKind,height:number,foliage:boolean) {
  const h=Math.max(0,Number.isFinite(height)?height:0);
  if(h<=1e-5)return 0;
  if(["oak","birch","willow","pine","alpine-pine"].includes(kind))return foliage?.5*clamp(h/6)**1.35:.12*clamp(h/6)**2;
  if(kind==="shrub")return .32*clamp(h/1.5)**1.4;
  return .44*clamp(h/.8)**1.4;
}

/** CPU reference for the vertex shader; displacement remains inside placement clearances. */
export function vegetationDisplacement(root:Root,flex:number,phase:number,frame:VegetationWindFrame):Root {
  const speed=Math.hypot(frame.wind.x,frame.wind.z);
  if(frame.reducedMotion||!Number.isFinite(speed)||speed<1e-6)return {x:0,y:0,z:0};
  const strength=clamp(frame.wind.strength),bend=clamp(flex,VEGETATION_MAX_BEND)*strength;
  const seconds=Number.isFinite(frame.seconds)?frame.seconds:0,p=Number.isFinite(phase)?phase:0;
  const spatial=(Number.isFinite(root.x)?root.x:0)*.013+(Number.isFinite(root.z)?root.z:0)*.017;
  const along=bend*(.62+.2*Math.sin(seconds*.72+p+spatial)+.08*Math.sin(seconds*1.71+p*1.37));
  const side=bend*.12*Math.sin(seconds*1.18+p*.83+spatial);
  const x=frame.wind.x/speed,z=frame.wind.z/speed;
  return {x:x*along+z*side,y:0,z:z*along-x*side};
}

export function vegetationWindUniforms() {
  return {vegetationWind:{value:new THREE.Vector2()},vegetationStrength:{value:0},vegetationSeconds:{value:0},vegetationPhases:{value:new THREE.Vector3()}};
}
type WindUniforms=ReturnType<typeof vegetationWindUniforms>;

// Keep coefficients identical to vegetationDisplacement so CPU bounds cover all passes.
const windShader=`
attribute vec3 windRoot;
attribute float windFlex;
attribute float windPhase;
uniform vec2 vegetationWind;
uniform float vegetationStrength;
uniform float vegetationSeconds;
uniform vec3 vegetationPhases;
vec2 vegetationOffset() {
  float speed = length(vegetationWind);
  if (speed < 0.000001) return vec2(0.0);
  vec2 direction = vegetationWind / speed;
  float bend = clamp(windFlex, 0.0, 0.5) * clamp(vegetationStrength, 0.0, 1.0);
  float spatial = windRoot.x * 0.013 + windRoot.z * 0.017;
  float along = bend * (0.62 + 0.2 * sin(vegetationPhases.x + windPhase + spatial) + 0.08 * sin(vegetationPhases.y + windPhase * 1.37));
  float side = bend * 0.12 * sin(vegetationPhases.z + windPhase * 0.83 + spatial);
  return direction * along + vec2(direction.y, -direction.x) * side;
}
`;

export function vegetationWindShader(uniforms:WindUniforms):THREE.MeshStandardMaterial["onBeforeCompile"] {
  return shader=>{
    Object.assign(shader.uniforms,uniforms);
    shader.vertexShader=shader.vertexShader.replace("#include <common>",`#include <common>\n${windShader}`)
      .replace("#include <beginnormal_vertex>",`#include <beginnormal_vertex>
        vec2 vegetationShear = vegetationOffset() / max(0.3, position.y - windRoot.y);
        objectNormal.y -= dot(objectNormal.xz, vegetationShear);
        objectNormal = normalize(objectNormal);`)
      .replace("#include <begin_vertex>",`#include <begin_vertex>\ntransformed.xz += vegetationOffset();`);
  };
}

/** Color, directional-light depth and point-light distance passes share exactly one bend. */
export function createVegetationWindMaterials() {
  const uniforms=vegetationWindUniforms(),material=new THREE.MeshStandardMaterial({vertexColors:true,roughness:1});
  const depth=new THREE.MeshDepthMaterial({depthPacking:THREE.RGBADepthPacking}),distance=new THREE.MeshDistanceMaterial();
  for(const target of [material,depth,distance]) {
    target.onBeforeCompile=vegetationWindShader(uniforms);
    target.customProgramCacheKey=()=>"earth35-vegetation-wind-v1";
  }
  return {uniforms,material,depth,distance,dispose:()=>{material.dispose();depth.dispose();distance.dispose();}};
}

export function updateVegetationWind(materials:Pick<ReturnType<typeof createVegetationWindMaterials>,"uniforms">,frame:VegetationWindFrame) {
  const {uniforms}=materials;
  const x=Number.isFinite(frame.wind.x)?frame.wind.x:0,z=Number.isFinite(frame.wind.z)?frame.wind.z:0;
  uniforms.vegetationWind.value.set(x,z);
  uniforms.vegetationStrength.value=frame.reducedMotion?0:clamp(frame.wind.strength);
  uniforms.vegetationSeconds.value=frame.reducedMotion?0:Number.isFinite(frame.seconds)?frame.seconds:0;
  const seconds=uniforms.vegetationSeconds.value,cycle=(value:number)=>((value%(Math.PI*2))+Math.PI*2)%(Math.PI*2);
  uniforms.vegetationPhases.value.set(cycle(seconds*.72),cycle(seconds*1.71),cycle(seconds*1.18));
}
