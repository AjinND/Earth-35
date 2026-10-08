"use client";
import {Component,memo,useCallback,useEffect,useLayoutEffect,useMemo,useRef,useState} from "react";
import {Canvas,useFrame,useThree} from "@react-three/fiber";
import {Html,OrbitControls} from "@react-three/drei";
import * as THREE from "three";
import type {OrbitControls as Controls} from "three-stdlib";
import type {Building,BusStop,ConstructionSiteView,World} from "@/lib/types";
import AgentAvatar from "./AgentAvatar";
import WorldEnvironment,{buildingHeight} from "./WorldEnvironment";
import TrafficScene,{BusPlatform} from "./TrafficScene";
import Roads from "./Roads";
import ScenicDestination,{RiverLandscape} from "./ScenicLandscape";
import {isScenicPlace} from "@/lib/scenic-layout";
import {clearsRoad,enclosedPlace,prototypeLayout,layoutPoint,type CityLayout} from "@/lib/city-layout";
import {separateResidents} from "@/lib/resident-spacing";
import {gatheringBenches,restingSeats,fountainObstacles} from "@/lib/gathering-furniture";
import IslandLandscape from "./IslandLandscape";
import ResidentialBuilding from "./ResidentialBuilding";
import OutdoorDestination from "./OutdoorDestination";
import ConstructionSite from "./ConstructionSite";
import WorldObjects from "./WorldObjects";
import BuildingWindows from "./BuildingWindows";
import {islandElevation} from "@/lib/island";
import IslandCamera,{fitIslandZoom,minimumPlanetZoom} from "./IslandCamera";
import PlanetLandscape from "./PlanetLandscape";
import {PlaybackTimeline} from "@/lib/playback-timeline";

export type CameraCommand={id:number;type:"in"|"out"|"reset"|"planet"|"island"|"settlement"|"residents"};
export type CameraView={percent:number;canZoomIn:boolean;canZoomOut:boolean;scale:"planet"|"island"|"settlement"|"residents";scaleMeters:number;scalePixels:number};
const colors=["#d3aa85","#c3c5b7","#c9b9a4","#c6d4c9","#dfcbb1"];
function Tree({x,z,size=1,origin={x:0,z:0},layout=prototypeLayout}:{x:number;z:number;size?:number;origin?:{x:number;z:number};layout?:CityLayout}) {
  if(!clearsRoad(x+origin.x,z+origin.z,1.5*size,layout))return null;
  return <group position={[x,0,z]} scale={size}><mesh castShadow position={[0,1,0]}><cylinderGeometry args={[.18,.3,2,5]}/><meshStandardMaterial color="#89755e"/></mesh><mesh castShadow position={[0,2.5,0]}><icosahedronGeometry args={[1.5,1]}/><meshStandardMaterial color="#62866a" roughness={1}/></mesh><mesh castShadow position={[.2,3.35,0]}><icosahedronGeometry args={[1.1,1]}/><meshStandardMaterial color="#7c9b73"/></mesh></group>;
}
function Bench({x,z,rotation=0}:{x:number;z:number;rotation?:number}) {
  return <group position={[x,0,z]} rotation={[0,rotation,0]}>
    <mesh castShadow position={[0,.75,0]}><boxGeometry args={[2.8,.22,.8]}/><meshStandardMaterial color="#9b7552"/></mesh>
    <mesh castShadow position={[0,1.25,-.35]}><boxGeometry args={[2.8,.8,.15]}/><meshStandardMaterial color="#9b7552"/></mesh>
    {[-1,1].map(x=><mesh key={x} position={[x,.35,0]}><boxGeometry args={[.18,.7,.7]}/><meshStandardMaterial color="#52685b"/></mesh>)}
  </group>;
}
function GatheringSpace({building:b,selected,onSelect,showLabels,layout}:{building:Building;selected:boolean;onSelect:()=>void;showLabels:boolean;layout:CityLayout}) {
  const park=b.type==="park",central=b.id==="park",garden=b.type==="garden",walk=b.type==="promenade",courtyard=b.type==="hangout";
  const width=park&&layout.version>0?12:8.4,depth=width;
  return <group position={[b.x,0,b.z]} onClick={e=>{e.stopPropagation();onSelect();}}>
    <mesh receiveShadow position={[0,.1,0]}><boxGeometry args={[width,.2,depth]}/><meshStandardMaterial color={selected?"#92ad78":park||garden?"#a8bb8a":"#d9cfb8"}/></mesh>
    <mesh receiveShadow position={[0,.22,0]}><boxGeometry args={[walk?2:width-2,.05,walk?depth-1:1.3]}/><meshStandardMaterial color="#eee1c7"/></mesh>
    {(central||b.type==="plaza")&&<><mesh position={[0,.3,0]}><cylinderGeometry args={[1.8,2,.5,24]}/><meshStandardMaterial color="#b8baa9"/></mesh><mesh position={[0,.6,0]}><cylinderGeometry args={[1.5,1.5,.12,24]}/><meshStandardMaterial color="#8eb4ba"/></mesh></>}
    {garden&&[-2.7,0,2.7].map(x=><group key={x}><mesh position={[x,.4,-2.8]}><boxGeometry args={[2,.5,2.3]}/><meshStandardMaterial color="#94755b"/></mesh>{[-.7,0,.7].map(z=><mesh key={z} position={[x,.75,-2.8+z]}><boxGeometry args={[1.6,.4,.35]}/><meshStandardMaterial color="#6f9365"/></mesh>)}</group>)}
    {courtyard&&<group>{[-3.5,3.5].flatMap(x=>[-3,3].map(z=><mesh key={`${x}-${z}`} castShadow position={[x,1.7,z]}><boxGeometry args={[.3,3.2,.3]}/><meshStandardMaterial color="#a38662"/></mesh>))}{[-3,-1,1,3].map(x=><mesh key={x} castShadow position={[x,3.35,0]}><boxGeometry args={[.6,.3,7]}/><meshStandardMaterial color="#a38662"/></mesh>)}</group>}
    {(garden||courtyard)&&<mesh castShadow position={[0,.9,2]}><boxGeometry args={[3.5,.25,1.8]}/><meshStandardMaterial color="#a38662"/></mesh>}
    {gatheringBenches(b.type).map((bench,i)=><Bench key={i} {...bench}/>)}
    {(park||walk)&&[-1,1].flatMap(side=>[-1,1].map(end=><Tree key={`${side}-${end}`} x={side*(width/2-2)} z={end*(depth/2-2)} size={.7} origin={b} layout={layout}/>))}
    {!park&&!walk&&<Tree x={-2.7} z={-2.7} size={.7} origin={b} layout={layout}/>}
    <Html position={[0,central?7:5,0]} center style={{display:central||showLabels||selected?"block":"none"}}><button className={`map-label ${park?"park-label":""} ${selected?"selected":""} ${central&&!showLabels?"settlement-marker":""}`} onClick={onSelect}>{central?(showLabels||selected?"CENTRAL PARK":"NEW EDEN"):b.name}</button></Html>
  </group>;
}
function Structure({building:b,buildings,selected,onSelect,index,showLabels,windows,layout,stop,site}:{building:Building;buildings:readonly Building[];selected:boolean;onSelect:()=>void;index:number;showLabels:boolean;windows:THREE.MeshStandardMaterial;layout:CityLayout;stop?:BusStop;site?:ConstructionSiteView}) {
  const house=b.type==="house",residential=b.capacity===1;
  const height=buildingHeight(b,index,layout.version>=2);
  const windowBoxes=useMemo(()=>Array.from({length:Math.floor(height/2)},(_,floor)=>[-2,0,2].flatMap(x=>[
    ...(floor!==0||x!==0?[{x,y:1.3+floor*2,z:3.37,width:.95,height:1.05,depth:.07}]:[]),
    {x:3.37,y:1.3+floor*2,z:x,width:.07,height:1.05,depth:.95}
  ])).flat(),[height]);
  const door=b.entrance??{side:"front",x:b.x,z:b.z+3.6};
  if((b.outdoorRadius??0)>0)return <OutdoorDestination building={b} buildings={buildings} selected={selected} onSelect={onSelect} showLabels={showLabels}/>;
  if(b.type==="construction")return <ConstructionSite building={b} site={site} selected={selected} onSelect={onSelect} showLabels={showLabels}/>;
  if(b.type==="bus_stop")return <BusPlatform building={b} stop={stop} layout={layout} selected={selected} onSelect={onSelect} showLabels={showLabels}/>;
  if(isScenicPlace(b.type))return <ScenicDestination building={b} selected={selected} onSelect={onSelect} showLabels={showLabels} organic={layout.version>=2}/>;
  if(b.type==="wilderness")return <group position={[b.x,islandElevation(b.x,b.z),b.z]}><mesh onClick={e=>{e.stopPropagation();onSelect();}} position={[0,.15,0]}><cylinderGeometry args={[2,2,.3,12]}/><meshStandardMaterial color="#c5ba97"/></mesh><Html center position={[0,4,0]} style={{display:showLabels||selected?"block":"none"}}><button className={`map-label ${selected?"selected":""}`} onClick={onSelect}>{b.name}</button></Html></group>;
  if(["park","garden","plaza","promenade","hangout"].includes(b.type)) return <GatheringSpace building={b} selected={selected} onSelect={onSelect} showLabels={showLabels} layout={layout}/>;
  if(layout.version>=2&&residential)return <ResidentialBuilding building={b} selected={selected} onSelect={onSelect} windows={windows}/>;
  return <group position={[b.x,0,b.z]}>
    {layout.version===1&&<mesh receiveShadow position={[0,.015,door.z-b.z]}><boxGeometry args={[layout.spacing-4.8,.03,1.2]}/><meshStandardMaterial color="#d7d2bc" roughness={1}/></mesh>}
    <mesh receiveShadow position={[0,.06,0]}><boxGeometry args={[7.4,.12,7.4]}/><meshStandardMaterial color="#b4baa9"/></mesh>
    <mesh castShadow receiveShadow position={[0,height/2+.2,0]} onClick={e=>{e.stopPropagation();onSelect();}}><boxGeometry args={[6.7,height,6.7]}/><meshStandardMaterial color={selected?"#a1c3aa":colors[index%colors.length]} roughness={.9}/></mesh>
    <mesh castShadow position={[0,height+.35,0]}><boxGeometry args={[7.1,.45,7.1]}/><meshStandardMaterial color={house?"#7d8374":"#e5e1d5"}/></mesh>
    {b.type==="mall"&&<group name="mall-atrium"><mesh position={[0,height+.65,0]}><boxGeometry args={[4,.4,4]}/><meshStandardMaterial color="#86b8bb" metalness={.25} roughness={.2}/></mesh>{[-2.4,2.4].map(x=><mesh key={x} position={[x,1.6,3.4]}><boxGeometry args={[1.7,2.5,.1]}/><meshStandardMaterial color="#83b4bd" metalness={.2} roughness={.25}/></mesh>)}</group>}
    {house&&<mesh castShadow position={[0,height+1.5,0]} rotation={[0,Math.PI/4,0]}><coneGeometry args={[5.3,2.8,4]}/><meshStandardMaterial color="#737e74"/></mesh>}
    <BuildingWindows boxes={windowBoxes} material={windows}/>
    <group name={`front-door-${b.id}`} position={[door.x-b.x,0,door.z-b.z-.22]}>
      <mesh position={[0,1.12,.015]}><boxGeometry args={[1.6,2.24,.12]}/><meshStandardMaterial color="#e8deca"/></mesh>
      <mesh position={[0,1.04,.09]}><boxGeometry args={[1.22,2.08,.08]}/><meshStandardMaterial color={residential?"#705944":"#43645d"}/></mesh>
      <mesh position={[.43,1,.16]}><sphereGeometry args={[.07,8,6]}/><meshStandardMaterial color="#dcc174" metalness={.6}/></mesh>
      <mesh receiveShadow position={[0,.12,.19]}><boxGeometry args={[1.9,.16,.5]}/><meshStandardMaterial color="#d2cab7"/></mesh>
    </group>
    {!residential&&<><mesh position={[0,2,3.55]} rotation={[.14,0,0]}><boxGeometry args={[7,.25,1.3]}/><meshStandardMaterial color={index%2?"#b87956":"#54775e"}/></mesh><Html center position={[0,height+2,0]} style={{display:showLabels||selected?"block":"none"}}><button className={`map-label ${selected?"selected":""}`} onClick={onSelect}>{b.name}</button></Html></>}
    {selected&&residential&&<Html position={[0,height+3,0]} center style={{pointerEvents:"none"}}><span className="map-label selected">{b.name}</span></Html>}
  </group>;
}
export function Scene({world,selected,onSelect,follow,command,onCameraChange,onIsland,immersive=false}:{world:World;selected:string;onSelect:(id:string)=>void;follow:string;command:CameraCommand;onCameraChange:(view:CameraView)=>void;onIsland?:()=>void;immersive?:boolean}) {
  const controls=useRef<Controls>(null);const {camera,size,gl}=useThree();
  const layout=world.geography??prototypeLayout;
  const playback=useRef(new PlaybackTimeline(2000));
  // Keep the world clock current even while the island detail is hidden in the atlas.
  useLayoutEffect(()=>{playback.current.ingest(world.minute,world.tickMs??5000,performance.now());},[world.minute,world.tickMs]);
  useEffect(()=>{gl.domElement.dataset.cityBuildings=String(world.buildings.filter(b=>enclosedPlace(b.type)).length);gl.domElement.dataset.constructionSites=String(world.buildings.filter(b=>b.type==="construction").length);gl.domElement.dataset.expandedDestinations=String(world.buildings.filter(b=>(b.outdoorRadius??0)>0).length);},[world.buildings,gl]);
  useEffect(()=>{gl.domElement.dataset.activeConstructionWorkers=String(world.construction?.sites.reduce((sum,site)=>sum+(site.workers?.length??0),0)??0);gl.domElement.dataset.placedObjects=String(world.objects?.filter(object=>object.status==="installed").length??0);},[world.construction,world.objects,gl]);
  const residents=useRef<THREE.Group>(null);
  const seats=useMemo(()=>restingSeats(world.agents,world.buildings),[world.agents,world.buildings]);
  const obstacles=useMemo(()=>fountainObstacles(world.buildings),[world.buildings]);
  useFrame(()=>{
    const models=residents.current?.children.filter(m=>m.visible);if(!models)return;
    // Avatars interpolate first (-2), spacing follows (-1), then cameras track
    // the displayed resident. Resolve every frame, including crossing paths.
    const positions=separateResidents(models.map(m=>({id:m.name,x:m.position.x,z:m.position.z,anchored:!!m.userData.seated||!!m.userData.transit})),obstacles);
    const byId=new Map(positions.map(p=>[p.id,p]));
    for(const model of models){const p=byId.get(model.name)!;model.position.x=p.x;model.position.z=p.z;}
  },-1);
  // Html labels are siblings of the canvas wrapper; listen on their common root.
  const mapElement=gl.domElement.closest<HTMLElement>(".world-canvas")??gl.domElement;
  const fitZoom=useMemo(()=>fitIslandZoom(size.width,size.height),[size.width,size.height]);
  const [showLabels,setShowLabels]=useState(false);
  const [islandLabels,setIslandLabels]=useState(true);
  const [planet,setPlanet]=useState(false);
  const [planetLabels,setPlanetLabels]=useState(false);
  const minZoom=minimumPlanetZoom(size.width,size.height),maxZoom=fitZoom*180;
  const reportCamera=useCallback((view:CameraView)=>{
    setShowLabels(view.percent>=1800);setIslandLabels(view.scale==="island");setPlanet(view.scale==="planet");setPlanetLabels(view.percent<.4);onCameraChange(view);
  },[onCameraChange]);
  const materials=useMemo(()=>({
    windows:new THREE.MeshStandardMaterial({color:"#4f6665",emissive:"#ffd49a",emissiveIntensity:.1}),
    lamps:new THREE.MeshStandardMaterial({color:"#fff1c9",emissive:"#ffd49a"}),
    pools:new THREE.MeshBasicMaterial({color:"#ffd49a",transparent:true,depthWrite:false,opacity:0}),
    roads:new THREE.MeshStandardMaterial({color:"#596963",roughness:1}),
  }),[]);
  useEffect(()=>()=>Object.values(materials).forEach(material=>material.dispose()),[materials]);
  const trees=useMemo(()=>Array.from({length:32},(_,i)=>layoutPoint(layout,12+(i%8)*6,47+Math.floor(i/8)*5)).filter(t=>!world.buildings.some(b=>Math.abs(t.x-b.x)<10&&Math.abs(t.z-b.z)<10)),[world.buildings,layout]);
  return <>
    {!immersive&&<PlanetLandscape showLabels={planetLabels} showIsland={planet} onIsland={onIsland??(()=>{})}/>}
    {(!planet||immersive)&&<>
    <WorldEnvironment world={world} {...materials}/>
    <IslandLandscape showLabels={!immersive&&islandLabels} buildings={world.buildings} layout={layout}/>
    <Roads material={materials.roads} layout={layout}/>
    {world.buildings.map((b,i)=><Structure key={b.id} building={b} buildings={world.buildings} site={world.construction?.sites.find(site=>site.id===b.id)} stop={world.transport?.stops.find(s=>s.id===b.id)} selected={selected===b.id} index={i} showLabels={showLabels} windows={materials.windows} layout={layout} onSelect={()=>onSelect(b.id)}/>)}
    <WorldObjects objects={world.objects??[]} selected={selected} onSelect={onSelect} showLabels={showLabels}/>
    <RiverLandscape buildings={world.buildings} layout={layout}/>
    <TrafficScene minute={world.minute} transport={world.transport} tickMs={world.tickMs??5000} layout={layout} timeline={playback.current}/>
    {[-58,-22,14,50].flatMap(x=>[-58,-22,14,50].map(z=><group key={`${x}-${z}`} position={[layoutPoint(layout,x,z).x,0,layoutPoint(layout,x,z).z]}>
      <mesh position={[0,1.65,0]}><cylinderGeometry args={[.1,.14,3.3,5]}/><meshStandardMaterial color="#596a66"/></mesh>
      <mesh position={[0,3.35,0]} material={materials.lamps}><sphereGeometry args={[.28,8,6]}/></mesh>
      <mesh position={[0,.16,0]} rotation={[-Math.PI/2,0,0]} material={materials.pools} raycast={()=>null}><circleGeometry args={[2.3,20]}/></mesh>
    </group>))}
    {trees.map((t,i)=><Tree key={i} {...t} size={.7+(i%3)*.2} layout={layout}/>)}
    <group ref={residents} name="city-residents">{world.agents.map(a=><AgentAvatar key={a.id} tickMs={world.tickMs??5000} minute={world.minute} agent={a} constructionWorking={world.construction?.sites.some(site=>site.workers?.some(worker=>worker.id===a.id))??false} seat={seats.get(a.id)} indoors={world.buildings.some(b=>b.id===a.location&&enclosedPlace(b.type))} selected={selected===a.id} onSelect={()=>onSelect(a.id)} timeline={playback.current}/>)}</group>
    {(!islandLabels||immersive)&&<><Html position={[layoutPoint(layout,-30,65).x,1,layoutPoint(layout,-30,65).z]} center style={{pointerEvents:"none"}}><span className="district-label">RESIDENTIAL DISTRICT</span></Html>
    <Html position={[layoutPoint(layout,30,-48).x,1,layoutPoint(layout,30,-48).z]} center style={{pointerEvents:"none"}}><span className="district-label">DOWNTOWN</span></Html></>}
    </>}
    {!immersive&&<><OrbitControls ref={controls} makeDefault domElement={mapElement} enableDamping minZoom={minZoom} maxZoom={maxZoom} minPolarAngle={.2} maxPolarAngle={Math.PI/2.4} enablePan screenSpacePanning={false} mouseButtons={{LEFT:THREE.MOUSE.PAN,MIDDLE:THREE.MOUSE.DOLLY,RIGHT:THREE.MOUSE.ROTATE}} touches={{ONE:THREE.TOUCH.PAN,TWO:THREE.TOUCH.DOLLY_PAN}}/>
      <IslandCamera world={world} selected={selected} follow={follow} command={command} controls={controls} residents={residents} fitZoom={fitZoom} onChange={reportCamera}/></>}
  </>;
}
class RenderBoundary extends Component<{children:React.ReactNode;fallback:React.ReactNode},{failed:boolean}> {state={failed:false};static getDerivedStateFromError(){return {failed:true};}render(){return this.state.failed?this.props.fallback:this.props.children;}}
export default memo(function City(props:{world:World;selected:string;onSelect:(id:string)=>void;follow:string;command:CameraCommand;onCameraChange:(view:CameraView)=>void;onIsland:()=>void}) {
  const [supported,setSupported]=useState<boolean|null>(null);
  useEffect(()=>{const canvas=document.createElement("canvas");const context=canvas.getContext("webgl2");setSupported(!!context);context?.getExtension("WEBGL_lose_context")?.loseContext();},[]);
  const fallback=<div className="city-fallback"><h2>Explore New Eden</h2><p>The 3D view is unavailable on this device. Every place is still accessible below.</p><div className="place-list">{props.world.buildings.map(b=><button key={b.id} onClick={()=>props.onSelect(b.id)}>{b.name}<small>{b.district}</small></button>)}</div></div>;
  if(supported===false)return fallback;if(supported===null)return <div className="city-loading">Preparing the island…</div>;
  return <RenderBoundary fallback={fallback}><Canvas className="world-canvas" orthographic shadows dpr={[1,1.5]} camera={{position:[1630,1550,1850],zoom:.3,near:.1,far:30000}} aria-label="Interactive 3D map of New Eden island"><Scene {...props}/></Canvas></RenderBoundary>;
});
