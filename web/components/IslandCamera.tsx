"use client";
import {useCallback,useEffect,useRef} from "react";
import {useFrame,useThree} from "@react-three/fiber";
import * as THREE from "three";
import type {OrbitControls as Controls} from "three-stdlib";
import type {World} from "@/lib/types";
import {COASTLINE,ISLAND_CENTER,ISLAND_BOUNDS,METERS_PER_MAP_UNIT,islandLandmarks,islandElevation} from "@/lib/island";
import type {CameraCommand,CameraView} from "./City";
import {ATLAS_BOUNDS,ATLAS_CENTER} from "@/lib/planet";

export const islandHome=new THREE.Vector3(1630,1550,1850);
const homeOffset=islandHome.clone().sub(new THREE.Vector3(ISLAND_CENTER.x,0,ISLAND_CENTER.z));
const atlasCorners=()=>[ATLAS_BOUNDS.minX,ATLAS_BOUNDS.maxX].flatMap(x=>[ATLAS_BOUNDS.minZ,ATLAS_BOUNDS.maxZ].map(z=>new THREE.Vector3(x,-2,z)));
export function fitPlanetZoom(width:number,height:number,offset=homeOffset) {
  const view=new THREE.OrthographicCamera();view.position.copy(offset);view.lookAt(0,0,0);view.updateMatrixWorld();
  const bounds=new THREE.Box3();atlasCorners().forEach(p=>bounds.expandByPoint(p.applyMatrix4(view.matrixWorldInverse)));
  const extent=bounds.getSize(new THREE.Vector3());
  return Math.min(Math.max(1,width-64)/extent.x,Math.max(1,height-220)/extent.y);
}
export function minimumPlanetZoom(width:number,height:number) {
  const diagonal=Math.hypot(ATLAS_BOUNDS.maxX-ATLAS_BOUNDS.minX,ATLAS_BOUNDS.maxZ-ATLAS_BOUNDS.minZ);
  return Math.min(Math.max(1,width-64),Math.max(1,height-220))/diagonal*.95;
}
export function fitIslandZoom(width:number,height:number) {
  const view=new THREE.OrthographicCamera();view.position.copy(islandHome);
  view.lookAt(ISLAND_CENTER.x,0,ISLAND_CENTER.z);view.updateMatrixWorld();
  const bounds=new THREE.Box3();
  for(const p of COASTLINE)bounds.expandByPoint(new THREE.Vector3(p.x,-1,p.z).applyMatrix4(view.matrixWorldInverse));
  for(const p of islandLandmarks)bounds.expandByPoint(new THREE.Vector3(p.x,islandElevation(p.x,p.z)+25,p.z).applyMatrix4(view.matrixWorldInverse));
  const extent=bounds.getSize(new THREE.Vector3());
  return Math.max(.05,Math.min(Math.max(1,width-48)/extent.x,Math.max(1,height-160)/extent.y));
}
type Transition={from:THREE.Vector3;to:THREE.Vector3;fromTarget:THREE.Vector3;toTarget:THREE.Vector3;fromZoom:number;toZoom:number;elapsed:number};
export default function IslandCamera({world,selected,follow,command,controls,residents,fitZoom,onChange}:{world:World;selected:string;follow:string;command:CameraCommand;controls:React.RefObject<Controls|null>;residents:React.RefObject<THREE.Group|null>;fitZoom:number;onChange:(view:CameraView)=>void}) {
  const {camera,gl,size}=useThree(),transition=useRef<Transition|null>(null),reduced=useRef(false);
  const viewport=useRef({width:size.width,height:size.height,selected});
  viewport.current={width:size.width,height:size.height,selected};
  const previousFit=useRef<number|null>(null),lastCommand=useRef<number|null>(null);
  const layout=world.geography,settlementRatio=layout?.version?2:8;
  const minZoom=minimumPlanetZoom(size.width,size.height),maxZoom=fitZoom*180;
  // Orthographic zoom alone does not extend the depth of the visible atlas.
  // Preserve direction and target while moving the camera above coarse geography.
  const adjustDistance=useCallback(()=>{
    const orbit=controls.current;if(!orbit)return;
    const offset=camera.position.clone().sub(orbit.target);
    const distance=homeOffset.length()*Math.max(1,fitZoom/camera.zoom);
    camera.position.copy(orbit.target).add(offset.normalize().multiplyScalar(distance));
    camera.far=Math.max(30000,distance*5);camera.updateProjectionMatrix();camera.updateMatrixWorld();
  },[camera,controls,fitZoom]);
  const report=useCallback(()=>{
    adjustDistance();
    const ratio=camera.zoom/fitZoom,target=controls.current?.target;
    const local=target&&target.x>ISLAND_BOUNDS.minX-1000&&target.x<ISLAND_BOUNDS.maxX+1000&&target.z>ISLAND_BOUNDS.minZ-1000&&target.z<ISLAND_BOUNDS.maxZ+1000;
    const scale=ratio<.55||!local?"planet":ratio<settlementRatio*.75?"island":ratio<26?"settlement":"residents";
    // Leave the mobile inspector readable while keeping its resident in the free view.
    // Shift the projection only; authoritative positions and the follow target stay intact.
    if(camera instanceof THREE.OrthographicCamera){
      const view=viewport.current,shift=scale==="residents"&&view.selected&&view.width<=540?Math.min(110,view.width*.3):0;
      if(shift)camera.setViewOffset(view.width,view.height,shift,0,view.width,view.height);
      else if(camera.view?.enabled)camera.clearViewOffset();
    }
    const metersPerPixel=METERS_PER_MAP_UNIT/camera.zoom;
    const power=10**Math.floor(Math.log10(metersPerPixel*90));
    const scaleMeters=[1,2,5,10].map(n=>n*power).filter(n=>n<=metersPerPixel*110).at(-1)??power;
    const percent=ratio<.01?Math.round(ratio*100000)/1000:Math.round(ratio*100);
    onChange({percent,canZoomIn:camera.zoom<maxZoom*(1-1e-6),canZoomOut:camera.zoom>minZoom*(1+1e-6),scale,scaleMeters,scalePixels:scaleMeters/metersPerPixel});
    gl.domElement.dataset.mapScale=scale;gl.domElement.dataset.cameraX=camera.position.x.toFixed(3);gl.domElement.dataset.cameraZ=camera.position.z.toFixed(3);
    gl.domElement.dataset.cameraZoom=String(camera.zoom);
    const direction=camera.getWorldDirection(new THREE.Vector3());
    gl.domElement.dataset.cameraDirectionX=String(direction.x);gl.domElement.dataset.cameraDirectionY=String(direction.y);gl.domElement.dataset.cameraDirectionZ=String(direction.z);
    const island=new THREE.Vector3(ISLAND_CENTER.x,0,ISLAND_CENTER.z).project(camera);
    gl.domElement.dataset.inhabitedIslandVisible=String(Math.abs(island.x)<=1&&Math.abs(island.y)<=1&&Math.abs(island.z)<=1);
    gl.domElement.dataset.planetBoundsVisible=String(atlasCorners().every(point=>{const p=point.project(camera);return Math.abs(p.x)<=1&&Math.abs(p.y)<=1&&Math.abs(p.z)<=1;}));
    gl.domElement.closest(".world-stage")?.setAttribute("data-map-scale",scale);
    if(scale==="planet")gl.domElement.closest(".world-stage")?.setAttribute("data-tone","light");
    if(controls.current){gl.domElement.dataset.targetX=controls.current.target.x.toFixed(3);gl.domElement.dataset.targetZ=controls.current.target.z.toFixed(3);}
  },[adjustDistance,camera,controls,fitZoom,gl,minZoom,maxZoom,onChange,settlementRatio]);
  useEffect(()=>{report();},[selected,size.width,size.height,report]);
  useEffect(()=>{
    const query=window.matchMedia("(prefers-reduced-motion: reduce)");const update=()=>{reduced.current=query.matches;};update();
    query.addEventListener("change",update);return()=>query.removeEventListener("change",update);
  },[]);
  const move=useCallback((target:THREE.Vector3,zoom:number,reset=false)=>{
    const orbit=controls.current;if(!orbit)return;
    orbit.enableDamping=false;orbit.update();orbit.enableDamping=!reduced.current;
    const position=reset?islandHome.clone():camera.position.clone().add(target.clone().sub(orbit.target));
    const nextZoom=THREE.MathUtils.clamp(zoom,minZoom,maxZoom);
    if(reduced.current){camera.position.copy(position);orbit.target.copy(target);camera.zoom=nextZoom;adjustDistance();orbit.update();report();transition.current=null;return;}
    transition.current={from:camera.position.clone(),to:position,fromTarget:orbit.target.clone(),toTarget:target,fromZoom:camera.zoom,toZoom:nextZoom,elapsed:0};
  },[adjustDistance,camera,controls,minZoom,maxZoom,report]);
  useEffect(()=>{
    const orbit=controls.current;if(!orbit)return;
    const initial=previousFit.current===null;
    const travel=transition.current;
    camera.zoom=THREE.MathUtils.clamp(initial?fitZoom:(travel?.toZoom??camera.zoom)/previousFit.current!*fitZoom,minZoom,maxZoom);
    if(travel){camera.position.copy(travel.to);orbit.target.copy(travel.toTarget);}
    if(initial){
      camera.position.copy(islandHome);orbit.target.set(ISLAND_CENTER.x,0,ISLAND_CENTER.z);
      // Selection may arrive before the canvas finishes its first layout.
      const agent=world.agents.find(a=>a.id===(follow||selected)),place=world.buildings.find(b=>b.id===selected),object=world.objects?.find(object=>object.id===selected);
      const model=residents.current?.children.find(m=>m.name===`resident-${agent?.id}`),point=model?.position??agent??place??object;
      if(point){const target=new THREE.Vector3(point.x,islandElevation(point.x,point.z),point.z);camera.position.add(target.clone().sub(orbit.target));orbit.target.copy(target);camera.zoom=fitZoom*(agent||object?55:place?.type==="construction"?35:12);}
    }
    transition.current=null;previousFit.current=fitZoom;adjustDistance();orbit.update();report();
  },[adjustDistance,camera,controls,fitZoom,minZoom,maxZoom,report]);
  useEffect(()=>{
    const orbit=controls.current;if(!orbit)return;
    const stop=()=>{transition.current=null;};orbit.addEventListener("start",stop);orbit.addEventListener("change",report);
    return()=>{orbit.removeEventListener("start",stop);orbit.removeEventListener("change",report);};
  },[controls,report]);
  useEffect(()=>{
    if(lastCommand.current===command.id)return;lastCommand.current=command.id;
    const orbit=controls.current;if(!orbit)return;
    if(command.type==="planet")move(new THREE.Vector3(ATLAS_CENTER.x,0,ATLAS_CENTER.z),fitPlanetZoom(size.width,size.height,camera.position.clone().sub(orbit.target)));
    else if(command.type==="reset"||command.type==="island")move(new THREE.Vector3(ISLAND_CENTER.x,0,ISLAND_CENTER.z),fitZoom,command.type==="reset");
    else if(command.type==="settlement")move(new THREE.Vector3(layout?.centerX??0,0,layout?.centerZ??0),fitZoom*settlementRatio);
    else if(command.type==="residents") {
      const agent=world.agents.find(a=>a.id===selected)??world.agents[0];
      const model=residents.current?.children.find(m=>m.name===`resident-${agent?.id}`),position=model?.position??agent;
      const x=position?.x??layout?.centerX??0,z=position?.z??(layout?.centerZ??0)+8;
      move(new THREE.Vector3(x,islandElevation(x,z),z),fitZoom*55);
    }else move(orbit.target.clone(),(transition.current?.toZoom??camera.zoom)*(command.type==="in"?1.25:.8));
  },[command,camera,controls,fitZoom,move,selected,size.width,size.height,world.agents,layout,settlementRatio]);
  useEffect(()=>{
    if(!selected||follow)return;
    const place=world.buildings.find(b=>b.id===selected),agent=world.agents.find(a=>a.id===selected),object=world.objects?.find(object=>object.id===selected);
    if(place)move(new THREE.Vector3(place.x,islandElevation(place.x,place.z),place.z),Math.max(camera.zoom,fitZoom*(place.type==="construction"?35:12)));
    else if(agent){const model=residents.current?.children.find(m=>m.name===`resident-${agent.id}`),position=model?.position??agent;move(new THREE.Vector3(position.x,islandElevation(position.x,position.z),position.z),fitZoom*55);}
    else if(object)move(new THREE.Vector3(object.x,islandElevation(object.x,object.z),object.z),fitZoom*55);
    // Live snapshots must never restart a selection transition.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  },[selected]);
  useEffect(()=>{
    const agent=world.agents.find(a=>a.id===follow);
    if(agent)move(new THREE.Vector3(agent.x,islandElevation(agent.x,agent.z),agent.z),Math.max(camera.zoom,fitZoom*55));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  },[follow]);
  useFrame((_,delta)=>{
    const orbit=controls.current;if(!orbit)return;
    const travel=transition.current;
    if(travel) {
      travel.elapsed+=delta;const t=Math.min(1,travel.elapsed/.65),ease=t*t*(3-2*t);
      camera.zoom=Math.exp(THREE.MathUtils.lerp(Math.log(travel.fromZoom),Math.log(travel.toZoom),ease));
      // Weight panning by zoom so the island remains in view across the atlas handoff.
      const pan=travel.toZoom>travel.fromZoom?1-(1-ease)*Math.min(1,travel.fromZoom/camera.zoom):ease*Math.min(1,travel.toZoom/camera.zoom);
      orbit.target.lerpVectors(travel.fromTarget,travel.toTarget,pan);
      const offset=new THREE.Vector3().lerpVectors(travel.from.clone().sub(travel.fromTarget),travel.to.clone().sub(travel.toTarget),ease);
      camera.position.copy(orbit.target).add(offset);adjustDistance();orbit.update();report();
      if(t===1)transition.current=null;
    }else if(follow) {
      const agent=world.agents.find(a=>a.id===follow);
      if(agent){const model=residents.current?.children.find(m=>m.name===`resident-${agent.id}`),position=model?.position??agent;
        const shift=new THREE.Vector3(position.x-orbit.target.x,islandElevation(position.x,position.z)-orbit.target.y,position.z-orbit.target.z).multiplyScalar(Math.min(1,delta*4));
        orbit.target.add(shift);camera.position.add(shift);orbit.update();report();}
    }
  });return null;
}
