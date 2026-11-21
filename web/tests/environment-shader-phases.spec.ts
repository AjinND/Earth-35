import {test,expect} from "@playwright/test";
import * as THREE from "three";
import {marineUniforms,updateMarineUniforms,marineShader} from "../lib/coastal-water";
import {vegetationWindUniforms,updateVegetationWind,createVegetationWindMaterials} from "../lib/vegetation-wind";

const TAU=Math.PI*2,frameSeconds=1/60,wind={x:6,z:4,strength:.6};
const float32=(values:number[])=>Array.from(new Float32Array(values));
const angularChange=(before:number,after:number)=>Math.atan2(Math.sin(after-before),Math.cos(after-before));
type Shader=Parameters<THREE.MeshStandardMaterial["onBeforeCompile"]>[0];

function samplers() {
  const marine=marineUniforms(),vegetation=vegetationWindUniforms();
  return [
    {name:"water",frequencies:[.91851,.19,.46456,.62642,.69],sample:(seconds:number)=>{
      updateMarineUniforms(marine,{seconds,wind});
      return float32([...marine.uSurfPhase.value.toArray(),marine.uSurfWashPhase.value]);
    }},
    {name:"vegetation",frequencies:[.72,1.71,1.18],sample:(seconds:number)=>{
      updateVegetationWind({uniforms:vegetation},{seconds,wind,reducedMotion:false});
      return float32(vegetation.vegetationPhases.value.toArray());
    }},
  ];
}

test("long-running water and vegetation retain smooth frame-sized Float32 phase advances",()=>{
  const seconds=600000;
  // An absolute float clock has already lost a 60 Hz frame at this world age.
  expect(Math.fround(seconds+frameSeconds)).toBe(Math.fround(seconds));
  for(const {name,frequencies,sample} of samplers()) {
    const before=sample(seconds),after=sample(seconds+frameSeconds);
    for(let index=0;index<frequencies.length;index++) {
      expect(after[index],`${name} phase ${index} stalled`).not.toBe(before[index]);
      const advance=angularChange(before[index],after[index]);
      expect(advance,`${name} phase ${index} reversed`).toBeGreaterThan(0);
      expect(Math.abs(advance-frequencies[index]*frameSeconds),`${name} phase ${index} lost frame precision`).toBeLessThan(1e-5);
    }
  }
});

test("phase wrapping preserves angular continuity after Float32 upload",()=>{
  for(const {name,frequencies,sample} of samplers())for(const [index,frequency] of frequencies.entries()) {
    const wrap=(Math.floor(600000*frequency/TAU)+1)*TAU/frequency;
    const before=sample(wrap-frameSeconds/2)[index],after=sample(wrap+frameSeconds/2)[index];
    expect(before,`${name} must approach its next wrap`).toBeGreaterThan(TAU-.04);
    expect(after,`${name} must wrap near zero`).toBeLessThan(.04);
    expect(Math.abs(angularChange(before,after)-frequency*frameSeconds)).toBeLessThan(1e-5);
    expect(Math.abs(Math.sin(after)-Math.sin(before))).toBeLessThan(.04);
    expect(Math.abs(Math.cos(after)-Math.cos(before))).toBeLessThan(.001);
  }
});

test("billion-second uniforms remain bounded and reduced vegetation clears every animation phase",()=>{
  const marine=marineUniforms(),vegetation=vegetationWindUniforms();
  for(const seconds of [-1e9,0,600000,1e9]) {
    updateMarineUniforms(marine,{seconds,wind});
    updateVegetationWind({uniforms:vegetation},{seconds,wind,reducedMotion:false});
    const phases=float32([...marine.uSurfPhase.value.toArray(),marine.uSurfWashPhase.value,...vegetation.vegetationPhases.value.toArray()]);
    expect(phases.every(Number.isFinite)).toBe(true);
    for(const phase of phases) {expect(phase).toBeGreaterThanOrEqual(0);expect(phase).toBeLessThan(TAU+1e-6);}
    const noise=float32(marine.uSurfNoise.value.toArray());
    expect(noise.every(Number.isFinite)).toBe(true);
    for(const [index,limit] of [10,20,20,10].entries())expect(Math.abs(noise[index])).toBeLessThanOrEqual(limit+1e-6);
    expect(marine.uSurfTime.value).toBe(seconds);expect(vegetation.vegetationSeconds.value).toBe(seconds);
  }
  updateVegetationWind({uniforms:vegetation},{seconds:1e9,wind,reducedMotion:true});
  expect(vegetation.vegetationStrength.value).toBe(0);
  expect(vegetation.vegetationSeconds.value).toBe(0);
  expect(vegetation.vegetationPhases.value.toArray()).toEqual([0,0,0]);
  updateMarineUniforms(marine,{seconds:0,wind});
  expect(marine.uSurfPhase.value.toArray()).toEqual([0,0,0,0]);expect(marine.uSurfWashPhase.value).toBe(0);
});

test("water and all vegetation rendering passes consume reduced phases instead of absolute shader time",()=>{
  const marine=marineUniforms();
  for(const shore of [false,true]) {
    const shader={vertexShader:THREE.ShaderLib.standard.vertexShader,fragmentShader:THREE.ShaderLib.standard.fragmentShader,uniforms:{}} as Shader;
    marineShader(marine,shore)(shader,{} as THREE.WebGLRenderer);
    const source=shader.vertexShader+shader.fragmentShader;
    expect(source).not.toMatch(/\buSurfTime\s*\*|\*\s*uSurfTime\b/);
    expect(source).toContain("uniform vec4 uSurfPhase");expect(source).toMatch(/\buSurfPhase\.[xyzw]\b/);
    expect(shader.uniforms.uSurfPhase).toBe(marine.uSurfPhase);
    expect(shader.uniforms.uSurfWashPhase).toBe(marine.uSurfWashPhase);
    expect(shader.uniforms.uSurfNoise).toBe(marine.uSurfNoise);
  }
  const vegetation=createVegetationWindMaterials();
  try {
    for(const [material,program] of [[vegetation.material,THREE.ShaderLib.standard],[vegetation.depth,THREE.ShaderLib.depth],[vegetation.distance,THREE.ShaderLib.distanceRGBA]] as const) {
      const shader={vertexShader:program.vertexShader,fragmentShader:program.fragmentShader,uniforms:{}} as Shader;
      material.onBeforeCompile(shader,{} as THREE.WebGLRenderer);
      expect(shader.vertexShader).not.toMatch(/\bvegetationSeconds\s*\*|\*\s*vegetationSeconds\b/);
      expect(shader.vertexShader).toContain("uniform vec3 vegetationPhases");
      for(const axis of ["x","y","z"])expect(shader.vertexShader).toContain(`vegetationPhases.${axis}`);
      expect(shader.uniforms.vegetationPhases).toBe(vegetation.uniforms.vegetationPhases);
    }
  } finally {vegetation.dispose();}
});
