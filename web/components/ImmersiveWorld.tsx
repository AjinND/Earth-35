"use client";
import {useCallback,useEffect,useMemo,useRef,useState} from "react";
import {Canvas,useFrame,useThree} from "@react-three/fiber";
import * as THREE from "three";
import {ArrowLeft,Map,Minus,Navigation,Plus,RotateCcw,Users,X} from "lucide-react";
import type {Agent,Building,PlacedWorldObject,World} from "@/lib/types";
import {api,time} from "@/lib/api";
import {Scene} from "./City";
import {enclosedPlace,layoutRoads,prototypeLayout,withinSettlement,type CityLayout} from "@/lib/city-layout";
import {isScenicPlace,scenicWalkable} from "@/lib/scenic-layout";
import {dryLand,organicStreets,svgLine} from "@/lib/organic-layout";
import {walkElevation,ISLAND_BOUNDS,islandPath} from "@/lib/island";
import {terrainStep} from "@/lib/terrain-travel";

import {ScenicMapTerrain,ScenicMapSymbol} from "./ScenicMap";

type Point={x:number;z:number};
type Journey=Point&{id:number};
const minViewZoom=.65,maxViewZoom=3;
export function walkable(point:Point,buildings:Building[],layout:CityLayout=prototypeLayout,objects:PlacedWorldObject[]=[]) {
  return (layout.version>=2?dryLand(point):withinSettlement(point.x,point.z,layout)&&scenicWalkable(point,buildings))&&!buildings.some(b=>enclosedPlace(b.type)&&Math.abs(point.x-b.x)<3.9&&Math.abs(point.z-b.z)<3.9)&&!objects.some(object=>object.status==="installed"&&Math.hypot(point.x-object.x,point.z-object.z)<1);
}
export function walkSegmentClear(from:Point,to:Point,buildings:Building[],layout:CityLayout=prototypeLayout,objects:PlacedWorldObject[]=[]) {
  const steps=Math.max(1,Math.ceil(Math.hypot(to.x-from.x,to.z-from.z)/.5));
  for(let i=1;i<=steps;i++)if(!walkable({x:from.x+(to.x-from.x)*i/steps,z:from.z+(to.z-from.z)*i/steps},buildings,layout,objects))return false;
  return true;
}
export function arrival(point:Point,buildings:Building[],layout:CityLayout=prototypeLayout,objects:PlacedWorldObject[]=[]):Point {
  if(walkable(point,buildings,layout,objects))return point;
  for(let radius=4;radius<16;radius+=2)for(const [dx,dz] of [[0,1],[1,0],[0,-1],[-1,0]]) {
    const next={x:point.x+dx*radius,z:point.z+dz*radius};if(walkable(next,buildings,layout,objects))return next;
  }
  return {x:layout.centerX,z:layout.centerZ+8};
}
function FirstPerson({world,follow,journey,paused,movement,onPosition,onRelease,zoom,onZoom}:{world:World;follow:string;journey:Journey;paused:boolean;movement:React.RefObject<Set<string>>;onPosition:(p:Point)=>void;onRelease:()=>void;zoom:number;onZoom:(factor:number)=>void}) {
  const {camera,gl,scene}=useThree();const yaw=useRef(0),pitch=useRef(0),report=useRef(0),followRef=useRef(follow),pausedRef=useRef(paused);
  followRef.current=follow;pausedRef.current=paused;
  useEffect(()=>{camera.position.set(journey.x,walkElevation(journey.x,journey.z)+1.65,journey.z);},[journey,camera]);
  useEffect(()=>{
    const canvas=gl.domElement,root=canvas.closest<HTMLElement>(".world-canvas")??canvas;
    const pointers=new globalThis.Map<number,{x:number;y:number}>();let drag: {x:number;y:number}|null=null,pinch=0;
    const distance=()=>{const [a,b]=Array.from(pointers.values());return a&&b?Math.hypot(a.x-b.x,a.y-b.y):0;};
    const down=(e:PointerEvent)=>{if(pausedRef.current||e.button!==0)return;pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});drag=pointers.size===1?{x:e.clientX,y:e.clientY}:null;pinch=distance();canvas.setPointerCapture(e.pointerId);};
    const move=(e:PointerEvent)=>{if(pausedRef.current||!pointers.has(e.pointerId))return;pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});if(pointers.size>1){const next=distance();if(pinch>0&&next>0)onZoom(next/pinch);pinch=next;return;}if(!drag){drag={x:e.clientX,y:e.clientY};return;}yaw.current-=(e.clientX-drag.x)*.004;pitch.current=THREE.MathUtils.clamp(pitch.current-(e.clientY-drag.y)*.004,-1.2,1.2);drag={x:e.clientX,y:e.clientY};};
    const up=(e:PointerEvent)=>{pointers.delete(e.pointerId);drag=null;pinch=0;};
    const wheel=(e:WheelEvent)=>{if(pausedRef.current)return;e.preventDefault();const pixels=e.deltaY*(e.deltaMode===1?16:e.deltaMode===2?canvas.clientHeight:1);onZoom(Math.exp(-THREE.MathUtils.clamp(pixels,-250,250)*.002));};
    const keydown=(e:KeyboardEvent)=>{if(pausedRef.current||e.target instanceof HTMLInputElement||e.target instanceof HTMLSelectElement)return;if(["KeyW","KeyA","KeyS","KeyD","ArrowUp","ArrowDown","ArrowLeft","ArrowRight","ShiftLeft","ShiftRight"].includes(e.code)){e.preventDefault();movement.current.add(e.code);if(followRef.current)onRelease();}};
    const keyup=(e:KeyboardEvent)=>{movement.current.delete(e.code);onPosition({x:camera.position.x,z:camera.position.z});};
    const clear=()=>{movement.current.clear();pointers.clear();drag=null;pinch=0;};
    canvas.addEventListener("pointerdown",down);canvas.addEventListener("pointermove",move);canvas.addEventListener("pointerup",up);canvas.addEventListener("pointercancel",up);
    root.addEventListener("wheel",wheel,{passive:false});
    window.addEventListener("keydown",keydown);window.addEventListener("keyup",keyup);window.addEventListener("blur",clear);
    canvas.dataset.controlsReady="true";
    return()=>{clear();delete canvas.dataset.controlsReady;canvas.removeEventListener("pointerdown",down);canvas.removeEventListener("pointermove",move);canvas.removeEventListener("pointerup",up);canvas.removeEventListener("pointercancel",up);root.removeEventListener("wheel",wheel);window.removeEventListener("keydown",keydown);window.removeEventListener("keyup",keyup);window.removeEventListener("blur",clear);};
  },[gl,camera,movement,onRelease,onZoom,onPosition]);
  useFrame((_,delta)=>{
    const dt=Math.min(delta,.05),agent=world.agents.find(a=>a.id===follow);
    const nextZoom=THREE.MathUtils.lerp(camera.zoom,zoom,1-Math.exp(-dt*12));
    if(Math.abs(camera.zoom-zoom)>.0001){camera.zoom=Math.abs(nextZoom-zoom)<.0001?zoom:nextZoom;camera.updateProjectionMatrix();}
    gl.domElement.dataset.viewZoom=String(Math.round(camera.zoom*100));
    if(agent){
      // Follow the rendered resident, including the same interpolation as the avatar.
      const model=scene.getObjectByName(`resident-${agent.id}`);
      const target=model?model.getWorldPosition(new THREE.Vector3()):new THREE.Vector3(agent.x,0,agent.z);
      const destination=arrival({x:target.x+2.5,z:target.z+3.5},world.buildings,world.geography,world.objects);
      camera.position.lerp(new THREE.Vector3(destination.x,walkElevation(destination.x,destination.z)+1.65,destination.z),1-Math.exp(-dt*5));
      camera.lookAt(target.x,target.y+1,target.z);
      const angles=new THREE.Euler().setFromQuaternion(camera.quaternion,"YXZ");yaw.current=angles.y;pitch.current=angles.x;
    }else{
      camera.rotation.set(pitch.current,yaw.current,0,"YXZ");
      if(!paused){const keys=movement.current;const forward=Number(keys.has("KeyW")||keys.has("ArrowUp"))-Number(keys.has("KeyS")||keys.has("ArrowDown"));const side=Number(keys.has("KeyD")||keys.has("ArrowRight"))-Number(keys.has("KeyA")||keys.has("ArrowLeft"));
        const layout=world.geography??prototypeLayout;
        const budget=dt*(keys.has("ShiftLeft")||keys.has("ShiftRight")?9:4)*layout.spacing/12;
        const step=new THREE.Vector3(side,0,-forward).normalize().applyAxisAngle(new THREE.Vector3(0,1,0),yaw.current).multiplyScalar(budget);
        if(layout.version>=4&&(side||forward)) {
          step.multiplyScalar(1+1.2*.08);
          const from={x:camera.position.x,z:camera.position.z},to=terrainStep(from,{x:from.x+step.x,z:from.z+step.z},budget);
          step.set(to.x-from.x,0,to.z-from.z);
        }
        if(walkSegmentClear({x:camera.position.x,z:camera.position.z},{x:camera.position.x+step.x,z:camera.position.z},world.buildings,layout,world.objects))camera.position.x+=step.x;
        if(walkSegmentClear({x:camera.position.x,z:camera.position.z},{x:camera.position.x,z:camera.position.z+step.z},world.buildings,layout,world.objects))camera.position.z+=step.z;
      }
    }
    if(!agent)camera.position.y=walkElevation(camera.position.x,camera.position.z)+1.65;
    report.current+=delta;if(report.current>.15){report.current=0;onPosition({x:camera.position.x,z:camera.position.z});}
  });return null;
}
const noCameraChange=()=>{};
const initialCommand={id:0,type:"reset" as const};
export default function ImmersiveWorld({world,userId,onExit,connection}:{world:World;userId?:string;onExit:()=>void;connection:string}) {
  const layout=world.geography??prototypeLayout;
  const initialCamera=useMemo(()=>({position:[layout.centerX,1.65,layout.centerZ+8] as [number,number,number],fov:70,near:.08,far:1000}),[layout.centerX,layout.centerZ]);
  const mapX=layout.version>=2?ISLAND_BOUNDS.minX-8:layout.centerX-layout.halfExtent-4,mapZ=layout.version>=2?ISLAND_BOUNDS.minZ-8:layout.centerZ-layout.halfExtent-4,mapSize=layout.version>=2?Math.max(ISLAND_BOUNDS.maxX-ISLAND_BOUNDS.minX,ISLAND_BOUNDS.maxZ-ISLAND_BOUNDS.minZ)+16:layout.halfExtent*2+8;
  const dialog=useRef<HTMLDialogElement>(null),movement=useRef(new Set<string>());
  const [map,setMap]=useState(false),[follow,setFollow]=useState(""),[selected,setSelected]=useState("");
  const [zoom,setZoom]=useState(1);
  const changeZoom=useCallback((factor:number)=>setZoom(current=>THREE.MathUtils.clamp(current*factor,minViewZoom,maxViewZoom)),[]);
  const [position,setPosition]=useState<Point>(()=>({x:layout.centerX,z:layout.centerZ+8})),[journey,setJourney]=useState<Journey>(()=>({x:layout.centerX,z:layout.centerZ+8,id:0}));
  const [mine,setMine]=useState<string[]>([]),[ownerError,setOwnerError]=useState("");
  const [scope,setScope]=useState<"mine"|"all">(userId?"mine":"all");
  const release=useRef(()=>setFollow("")).current;
  useEffect(()=>{dialog.current?.showModal();const previous=document.body.style.overflow;document.body.style.overflow="hidden";return()=>{document.body.style.overflow=previous;};},[]);
  useEffect(()=>{if(!userId)return;let active=true;api<Agent[]>("/owner/agents").then(rows=>{if(active)setMine(rows.map(a=>a.id));}).catch(()=>{if(active)setOwnerError("Your agents could not be loaded. All residents are still available.");});return()=>{active=false;};},[userId]);
  useEffect(()=>{if(follow&&!world.agents.some(a=>a.id===follow))setFollow("");},[world.agents,follow]);
  const travel=(p:Point)=>{setFollow("");setSelected("");setJourney({...arrival(p,world.buildings,layout,world.objects),id:Date.now()});setMap(false);movement.current.clear();};
  const startFollow=(id:string)=>{const a=world.agents.find(a=>a.id===id);if(!a)return;setJourney({...arrival({x:a.x+2.5,z:a.z+3.5},world.buildings,layout,world.objects),id:Date.now()});setFollow(id);setSelected(id);setMap(false);};
  const followed=world.agents.find(a=>a.id===follow),picked=world.agents.find(a=>a.id===selected),place=world.buildings.find(b=>b.id===selected),object=world.objects?.find(object=>object.id===selected);
  const agents=world.agents.filter(a=>scope==="all"||mine.includes(a.id));
  return <dialog ref={dialog} className="immersive-world" aria-label="Explore New Eden in first person" onCancel={e=>{e.preventDefault();if(map)setMap(false);else onExit();}}>
    <Canvas className="world-canvas" shadows dpr={[1,1.5]} camera={initialCamera} aria-label="First-person view of New Eden">
      <Scene world={world} selected={selected} onSelect={setSelected} follow="" command={initialCommand} onCameraChange={noCameraChange} immersive/>
      <FirstPerson world={world} follow={follow} journey={journey} paused={map} movement={movement} onPosition={setPosition} onRelease={release} zoom={zoom} onZoom={changeZoom}/>
    </Canvas>
    <header className="world-hud"><button onClick={onExit}><ArrowLeft size={16}/>Back to dashboard</button><div><span className="eyebrow">EARTH 35 / NEW EDEN</span><strong>{time(world.minute)} · {world.weather}</strong></div><button aria-expanded={map} onClick={()=>{movement.current.clear();setMap(!map);}}><Map size={17}/>{map?"Close map":"World map"}</button></header>
    {connection!=="live"&&<div className="immersive-connection" role="status">{connection==="offline"?"Disconnected · showing the last received world state":connection==="polling"?"Connected · periodic updates":"Connecting to the world…"}</div>}
    {!map&&<><span className="world-crosshair" aria-hidden="true">+</span><div className="world-location"><span className="eyebrow">{followed?"FOLLOWING ONE RESIDENT":"FREE EXPLORATION"}</span><h2>{followed?followed.name:"You’re in New Eden."}</h2><p>{followed?followed.activity.toLowerCase().replaceAll("_"," "):"Take a walk. See where life takes you."}</p>{followed&&<button onClick={release}>Stop following</button>}<output aria-label="Your world position">{position.x.toFixed(1)}, {position.z.toFixed(1)}</output></div>
    {(picked||place||object)&&!followed&&<aside className="world-selection"><button className="dismiss-selection" aria-label="Close selection" onClick={()=>setSelected("")}><X size={14}/></button><strong>{picked?.name||place?.name||object?.text}</strong>{picked?<button onClick={()=>startFollow(picked.id)}>Follow agent</button>:(place||object)&&<button onClick={()=>travel((place??object)!)}>Go to location</button>}</aside>}
    <div className="view-zoom" role="group" aria-label="First-person zoom controls"><button aria-label="Zoom out view" title="Zoom out" disabled={zoom<=minViewZoom} onClick={()=>changeZoom(1/1.2)}><Minus size={17}/></button><output aria-label="View zoom">{Math.round(zoom*100)}%</output><button aria-label="Zoom in view" title="Zoom in" disabled={zoom>=maxViewZoom} onClick={()=>changeZoom(1.2)}><Plus size={17}/></button><button aria-label="Reset view zoom" title="Reset zoom" onClick={()=>setZoom(1)}><RotateCcw size={16}/></button></div>
    <footer className="world-help">W A S D / arrows to walk <span>·</span> Drag to look <span>·</span> Scroll / pinch to zoom <span>·</span> Shift to move faster <span>·</span> Esc to leave</footer>
    <div className="walk-pad" aria-label="Walking controls">{[["KeyW","Forward"],["KeyA","Left"],["KeyS","Backward"],["KeyD","Right"]].map(([key,label])=><button key={key} aria-label={`Walk ${label.toLowerCase()}`} onPointerDown={e=>{e.currentTarget.setPointerCapture(e.pointerId);release();movement.current.add(key);}} onPointerUp={()=>movement.current.delete(key)} onPointerCancel={()=>movement.current.delete(key)}>{label}</button>)}</div></>}
    {map&&<section className="world-map-panel" aria-label="World map and residents"><div className="world-map-heading"><div><span className="eyebrow">FIND YOUR WAY</span><h2>A whole island to discover.</h2><p>Select a place to travel. Select a resident to follow.</p></div><button aria-label="Close world map" onClick={()=>setMap(false)}><X/></button></div><div className="world-map-grid"><div className="city-plan"><svg viewBox={`${mapX} ${mapZ} ${mapSize} ${mapSize}`} role="group" aria-label="Live city map"><rect x={mapX} y={mapZ} width={mapSize} height={mapSize} rx="4" fill="#d9e0ca"/>{layout.version>=2?<><path d={islandPath} fill="#d9e0ca" stroke="#cabd98" strokeWidth="7"/>{organicStreets.map((points,i)=><path key={i} d={svgLine(points)} stroke="#acb9ad" strokeWidth="3" fill="none"/>)}</>:<>{layoutRoads(layout,true).map(v=><path key={`x${v}`} stroke="#acb9ad" strokeWidth="3" d={`M ${v} ${mapZ} V ${mapZ+mapSize}`}/>)}{layoutRoads(layout,false).map(v=><path key={`z${v}`} stroke="#acb9ad" strokeWidth="3" d={`M ${mapX} ${v} H ${mapX+mapSize}`}/>)}</>}<ScenicMapTerrain buildings={world.buildings} organic={layout.version>=2}/>{world.buildings.map(b=><g key={b.id} role="button" tabIndex={0} aria-label={`Visit ${b.name}`} onClick={()=>travel(b)} onKeyDown={e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();travel(b);}}}>{isScenicPlace(b.type)?<ScenicMapSymbol building={b}/>:<rect x={b.x-3} y={b.z-3} width="6" height="6" rx=".7" fill={!enclosedPlace(b.type)?"#719976":"#bd9e7d"}/>}<title>{b.name}</title></g>)}{world.agents.map(a=><g key={a.id} role="button" tabIndex={0} aria-label={`Follow ${a.name}`} onClick={()=>startFollow(a.id)} onKeyDown={e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();startFollow(a.id);}}}><circle cx={a.x} cy={a.z} r={mine.includes(a.id)?2:1.5} fill={mine.includes(a.id)?"#ad5a2e":"#465f62"} stroke="white" strokeWidth=".5"/><circle cx={a.x} cy={a.z} r="3.5" fill="transparent"/><title>{a.name}</title></g>)}{world.objects?.filter(object=>object.status==="installed").map(object=><g key={object.id} role="button" tabIndex={0} aria-label={`Visit sign: ${object.text}`} onClick={()=>travel(object)} onKeyDown={e=>{if(e.key==="Enter"||e.key===" "){e.preventDefault();travel(object);}}}><rect x={object.x-1} y={object.z-.6} width="2" height="1.2" fill={object.kind==="advertisement"?"#a67737":"#42674d"}/><title>{object.text}</title></g>)}<circle cx={position.x} cy={position.z} r="2.2" fill="#345eed" stroke="white" strokeWidth="1"/></svg><p><i/> You <span>● Your agents</span> · Residents shown live</p></div><div className="world-map-lists"><h3><Users size={16}/> Residents</h3><div className="map-scope"><button aria-pressed={scope==="mine"} disabled={!userId} onClick={()=>setScope("mine")}>My agents</button><button aria-pressed={scope==="all"} onClick={()=>setScope("all")}>All residents</button></div>{ownerError&&<p role="alert">{ownerError}</p>}{!userId&&<p>Sign in from the dashboard to identify your agents.</p>}{!agents.length&&<p>{scope==="mine"?"You have no agents in this city yet.":"The city is waiting for its first residents."}</p>}{agents.map(a=><button className="map-destination" key={a.id} onClick={()=>startFollow(a.id)}><span><strong>{a.name}</strong><small>{world.buildings.find(b=>b.id===a.location)?.name||"Travelling"} · {a.activity.toLowerCase().replaceAll("_"," ")}</small></span><span>Follow →</span></button>)}<h3><Navigation size={16}/> Go somewhere</h3>{world.buildings.map(b=><button className="map-destination" key={b.id} onClick={()=>travel(b)}><span><strong>{b.name}</strong><small>{b.district}</small></span><span>Visit →</span></button>)}{!!world.objects?.length&&<><h3>Resident signs</h3>{world.objects.filter(object=>object.status==="installed").map(object=><button className="map-destination" key={object.id} onClick={()=>travel(object)}><span><strong>{object.text}</strong><small>{object.kind==="advertisement"?"Advertisement":"Signboard"}</small></span><span>Visit →</span></button>)}</>}</div></div></section>}
  </dialog>;
}

