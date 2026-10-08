"use client";
import {Html} from "@react-three/drei";
import type {Building,ConstructionSiteView} from "@/lib/types";
import {constructionProgress,constructionStage} from "@/lib/construction";

export default function ConstructionSite({building:b,site,selected,onSelect,showLabels}:{building:Building;site?:ConstructionSiteView;selected:boolean;onSelect:()=>void;showLabels:boolean}) {
  const floors=b.plannedType==="house"?2:b.plannedType==="office"?5:4,steel="#777f79",concrete="#bcb9aa",yellow="#d9af43";
  const progress=constructionProgress(site),frame=Math.max(0,Math.min(1,(progress.fraction-.12)/.64))*floors;
  const workers=site?.workers??[];
  return <group name={`construction-site-${b.id}`} position={[b.x,0,b.z]} userData={{progress:progress.percent,activeWorkers:workers.length}} onClick={e=>{e.stopPropagation();onSelect();}}>
    <mesh receiveShadow position={[0,.05,0]}><boxGeometry args={[7.2,.1,7.2]}/><meshStandardMaterial color={selected?"#c8bc83":"#b8ab8e"}/></mesh>
    {progress.fraction<.12&&[-2.5,2.5].flatMap(x=>[-2.3,2.3].map(z=><mesh key={`${x}-${z}`} receiveShadow position={[x,.16,z]}><boxGeometry args={[.85,.25+progress.fraction*2,.85]}/><meshStandardMaterial color={concrete}/></mesh>))}
    {Array.from({length:Math.ceil(frame)},(_,i)=>{const height=Math.max(.12,Math.min(1,frame-i))*2.6;return <group key={i} position={[0,i*2.6,0]}><mesh position={[0,.35,0]} receiveShadow castShadow><boxGeometry args={[5.8,.25,5.5]}/><meshStandardMaterial color={concrete}/></mesh>{[-2.6,2.6].flatMap(x=>[-2.4,2.4].map(z=><mesh key={`${x}-${z}`} position={[x,.35+height/2,z]} castShadow><boxGeometry args={[.3,height,.3]}/><meshStandardMaterial color={steel}/></mesh>))}{progress.fraction>.76&&<mesh position={[-2.65,1.6,0]}><boxGeometry args={[.16,2.1,4.5]}/><meshStandardMaterial color="#cabdae"/></mesh>}</group>;})}
    {frame>0&&<mesh position={[.6,frame*1.3,-.6]} castShadow><boxGeometry args={[1.4,frame*2.6,1.4]}/><meshStandardMaterial color="#a8a99e"/></mesh>}
    {[-1,1].map(side=><group key={side}><mesh position={[side*3.5,.85,0]}><boxGeometry args={[.1,1.7,7.1]}/><meshStandardMaterial color="#76958b" wireframe/></mesh><mesh position={[0,.85,side*3.5]}><boxGeometry args={[7.1,1.7,.1]}/><meshStandardMaterial color="#76958b" wireframe/></mesh><mesh position={[0,.75,side*3.52]}><boxGeometry args={[7.1,.18,.15]}/><meshStandardMaterial color={yellow}/></mesh></group>)}
    <group name="tower-crane" position={[-2.8,0,-2.8]}><mesh position={[0,floors*1.3+2.5,0]} castShadow><boxGeometry args={[.4,floors*2.6+5,.4]}/><meshStandardMaterial color={yellow}/></mesh><mesh position={[2,floors*2.6+4.7,0]} castShadow><boxGeometry args={[8,.35,.4]}/><meshStandardMaterial color={yellow}/></mesh><mesh position={[5.4,floors*2.6+2.7,0]}><boxGeometry args={[.05,4,.05]}/><meshStandardMaterial color="#4e5b57"/></mesh><mesh position={[5.4,floors*2.6+.65,0]}><torusGeometry args={[.18,.06,6,10,Math.PI*1.5]}/><meshStandardMaterial color="#4e5b57"/></mesh><mesh position={[-1.6,floors*2.6+4.2,0]}><boxGeometry args={[1.4,.8,.7]}/><meshStandardMaterial color="#8a8e82"/></mesh></group>
    {[-2,-.8,.4].map((x,i)=><mesh key={x} position={[x,.3,2.7]}><boxGeometry args={[.7,.5,.7]}/><meshStandardMaterial color={i===1?"#ac7958":"#b8afa0"}/></mesh>)}
    <mesh position={[0,1.2,3.57]}><boxGeometry args={[1.9,.65,.08]}/><meshStandardMaterial color={yellow}/></mesh>
    <Html center position={[0,floors*2.6+6,0]} style={{display:showLabels||selected?"block":"none"}}><button className={`map-label ${selected?"selected":""}`} data-construction-progress={progress.percent} data-active-workers={workers.length} onClick={onSelect}>{b.name}<small style={{display:"block",fontSize:9}}>{progress.percent}% · {constructionStage(progress.fraction)}</small><small style={{display:"block",fontSize:9}}>{workers.length?`${workers.length} resident${workers.length===1?"":"s"} building`:"Awaiting labor"}</small></button></Html>
  </group>;
}
