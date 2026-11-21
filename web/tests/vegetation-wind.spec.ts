import {test,expect} from "@playwright/test";
import * as THREE from "three";
import {VEGETATION_MAX_BEND,vegetationDisplacement,vegetationFlex,vegetationPhase,createVegetationWindMaterials,updateVegetationWind} from "../lib/vegetation-wind";
import {createVegetationTiles,plantGeometry} from "../lib/vegetation-geometry";
import {windAt} from "../lib/wind";
import type {Plant,PlantKind} from "../lib/living-environment";

const root={x:-595,y:24,z:-100};
const plant=(id:number,kind:PlantKind,x=-595,z=-100):Plant=>({id,kind,x,y:24,z,size:1.2,angle:.42,radius:3,habitat:"forest",detail:["grass","fern","reeds","flowers","alpine-flowers"].includes(kind)});

test("plant roots stay fixed and crowns and herbs flex more than woody trunks",()=>{
  const frame={seconds:57,wind:windAt(720,"Rain"),reducedMotion:false};
  for(const kind of ["oak","birch","willow","pine","alpine-pine","shrub","fern","grass","reeds","flowers","alpine-flowers"] as PlantKind[]) {
    expect(vegetationFlex(kind,0,true)).toBe(0);
    expect(vegetationDisplacement(root,vegetationFlex(kind,0,true),.6,frame)).toEqual({x:0,y:0,z:0});
    expect(vegetationFlex(kind,-1,true)).toBe(0);
  }
  expect(vegetationFlex("oak",4,false)).toBeLessThan(.12);
  expect(vegetationFlex("oak",4,true)).toBeGreaterThan(vegetationFlex("oak",4,false));
  expect(vegetationFlex("grass",.6,true)).toBeGreaterThan(vegetationFlex("oak",4,false));
  expect(vegetationFlex("oak",100,true)).toBe(VEGETATION_MAX_BEND);
});

test("vegetation follows shared wind with deterministic local flutter and bounded displacement",()=>{
  const phase=vegetationPhase(121,root.x,root.z);
  expect(vegetationPhase(121,root.x,root.z)).toBe(phase);
  expect(vegetationPhase(122,root.x,root.z)).not.toBe(phase);
  expect(vegetationPhase(121,root.x+10,root.z)).not.toBe(phase);
  for(const weather of ["Clear","Overcast","Rain"])for(let second=0;second<400;second+=.731) {
    const wind=windAt(second/5,weather),frame={seconds:second,wind,reducedMotion:false};
    const bend=vegetationDisplacement(root,.5,phase,frame),length=Math.hypot(bend.x,bend.z);
    expect([bend.x,bend.y,bend.z].every(Number.isFinite)).toBe(true);
    expect(length).toBeLessThanOrEqual(VEGETATION_MAX_BEND);
    expect(bend.y).toBe(0);
    expect(bend.x*wind.x+bend.z*wind.z).toBeGreaterThan(0);
    expect(vegetationDisplacement(root,.5,phase,frame)).toEqual(bend);
  }
  const wind={x:12,z:0,strength:1};
  const bend=vegetationDisplacement(root,.5,phase,{seconds:20,wind,reducedMotion:false});
  const reverse=vegetationDisplacement(root,.5,phase,{seconds:20,wind:{...wind,x:-12},reducedMotion:false});
  expect(reverse.x).toBe(-bend.x);expect(reverse.z).toBe(-bend.z);
});

test("reduced motion and calm air hold vegetation still, and invalid samples stay finite",()=>{
  for(const seconds of [0,1,10000])expect(vegetationDisplacement(root,.5,.2,{seconds,wind:windAt(720,"Rain"),reducedMotion:true})).toEqual({x:0,y:0,z:0});
  expect(vegetationDisplacement(root,.5,.2,{seconds:1,wind:{x:0,z:0,strength:1},reducedMotion:false})).toEqual({x:0,y:0,z:0});
  for(const input of [
    {seconds:NaN,wind:{x:4,z:2,strength:Infinity},reducedMotion:false},
    {seconds:Infinity,wind:{x:NaN,z:2,strength:1},reducedMotion:false},
    {seconds:2,wind:{x:1,z:2,strength:1},reducedMotion:false},
  ])expect(Object.values(vegetationDisplacement({x:NaN,y:Infinity,z:NaN},Infinity,NaN,input)).every(Number.isFinite)).toBe(true);
});

test("merged plants carry their own roots, phase and flexibility without changing ground contact",()=>{
  const plants=[plant(1,"birch"),plant(2,"oak",-590,-102),plant(3,"grass",-591,-101)];
  const surface=(x:number,z:number)=>24+x*.01+z*.02,result=createVegetationTiles(plants,surface);
  try {
    for(const layer of result.tiles.flatMap(tile=>[tile.canopy,tile.understory]).filter((value):value is THREE.BufferGeometry=>!!value)) {
      const position=layer.getAttribute("position"),roots=layer.getAttribute("windRoot"),flex=layer.getAttribute("windFlex"),phase=layer.getAttribute("windPhase");
      expect(roots.count).toBe(position.count);expect(flex.count).toBe(position.count);expect(phase.count).toBe(position.count);
      for(let i=0;i<position.count;i++) {
        const p=plants.find(plant=>Math.abs(plant.x-roots.getX(i))<.001&&Math.abs(plant.z-roots.getZ(i))<.001)!;
        expect(p).toBeDefined();
        expect(roots.getY(i)).toBeCloseTo(surface(p.x,p.z)-.06,4);
        expect(phase.getX(i)).toBeCloseTo(vegetationPhase(p.id,p.x,p.z),5);
        expect(flex.getX(i)).toBeGreaterThanOrEqual(0);expect(flex.getX(i)).toBeLessThanOrEqual(VEGETATION_MAX_BEND);
      }
    }
    const tree=plants[0],single=createVegetationTiles([tree],surface),tile=single.tiles[0],geometry=tile.canopy!,template=plantGeometry(tree.kind);
    try {
      const source=template.getAttribute("position"),positions=geometry.getAttribute("position"),flex=geometry.getAttribute("windFlex");
      let fixedRoots=0;
      for(let i=0;i<source.count;i++)if(Math.abs(source.getY(i))<1e-5) {
        expect(flex.getX(i)).toBe(0);
        expect(positions.getY(i)-surface(positions.getX(i)+tile.x,positions.getZ(i)+tile.z)).toBeCloseTo(-.06,4);fixedRoots++;
      }
      expect(fixedRoots).toBeGreaterThan(4);
    } finally {single.dispose();template.dispose();}
  } finally {result.dispose();}
});

test("color and shadow materials use the same wind uniforms and deformation",()=>{
  const materials=createVegetationWindMaterials(),frame={seconds:3602,wind:windAt(720.4,"Rain"),reducedMotion:false};
  try {
    updateVegetationWind(materials,frame);
    expect(materials.uniforms.vegetationSeconds.value).toBe(frame.seconds);
    expect(materials.uniforms.vegetationWind.value.toArray()).toEqual([frame.wind.x,frame.wind.z]);
    for(const [material,source] of [[materials.material,THREE.ShaderLib.standard],[materials.depth,THREE.ShaderLib.depth],[materials.distance,THREE.ShaderLib.distanceRGBA]] as const) {
      const shader={vertexShader:source.vertexShader,fragmentShader:source.fragmentShader,uniforms:{}} as Parameters<typeof material.onBeforeCompile>[0];
      material.onBeforeCompile(shader,{} as THREE.WebGLRenderer);
      expect(shader.vertexShader).toContain("transformed.xz += vegetationOffset()");
      expect(shader.vertexShader).toContain("attribute vec3 windRoot");
      expect(shader.uniforms.vegetationSeconds).toBe(materials.uniforms.vegetationSeconds);
      expect(material.customProgramCacheKey()).toBe(materials.material.customProgramCacheKey());
    }
    updateVegetationWind(materials,{...frame,reducedMotion:true});
    expect(materials.uniforms.vegetationStrength.value).toBe(0);expect(materials.uniforms.vegetationSeconds.value).toBe(0);
  } finally {materials.dispose();}
});
