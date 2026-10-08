"use client";

import {useEffect,useMemo} from "react";
import {Html} from "@react-three/drei";
import * as THREE from "three";
import type {PlacedWorldObject} from "@/lib/types";
import {walkElevation} from "@/lib/island";

function Signboard({object,selected,onSelect,showLabels}:{object:PlacedWorldObject;selected:boolean;onSelect:()=>void;showLabels:boolean}) {
  const advertisement=object.kind==="advertisement";
  const texture=useMemo(()=>{
    const canvas=document.createElement("canvas");canvas.width=768;canvas.height=440;
    const ctx=canvas.getContext("2d")!;
    ctx.fillStyle=advertisement?"#f2d389":"#f8f4de";ctx.fillRect(0,0,canvas.width,canvas.height);
    ctx.strokeStyle=advertisement?"#8b582a":"#3d614c";ctx.lineWidth=16;ctx.strokeRect(10,10,748,420);
    ctx.fillStyle=advertisement?"#674023":"#294938";ctx.font="bold 45px Arial";ctx.textAlign="center";
    ctx.fillText(advertisement?"LOCAL BUSINESS":"NEW EDEN",384,72);
    let lines:string[]=[];
    for(const fontSize of [50,42,36,30,26]) {
      ctx.font=`bold ${fontSize}px Arial`;lines=[];let line="";
      for(const word of object.text.split(/\s+/)) {
        const candidate=line?`${line} ${word}`:word;
        if(ctx.measureText(candidate).width>650&&line){lines.push(line);line=word;}else line=candidate;
      }
      if(line)lines.push(line);
      if(lines.length<=5)break;
    }
    for(const [i,text] of lines.entries())ctx.fillText(text,384,150+i*Math.min(55,250/Math.max(1,lines.length)),650);
    const value=new THREE.CanvasTexture(canvas);value.colorSpace=THREE.SRGBColorSpace;return value;
  },[advertisement,object.text]);
  useEffect(()=>()=>texture.dispose(),[texture]);
  return <group name={`world-object-${object.id}`} position={[object.x,walkElevation(object.x,object.z),object.z]} onClick={event=>{event.stopPropagation();onSelect();}}>
    {[-.52,.52].map(x=><mesh key={x} castShadow position={[x,.75,0]}><boxGeometry args={[.1,1.5,.1]}/><meshStandardMaterial color="#70634e"/></mesh>)}
    <mesh castShadow position={[0,1.5,0]}><boxGeometry args={[1.4,.85,.3]}/><meshStandardMaterial color={selected?"#d4aa43":"#6f674f"}/></mesh>
    {[-1,1].map(side=><mesh key={side} position={[0,1.5,side*.154]} rotation={[0,side===-1?Math.PI:0,0]}><planeGeometry args={[1.33,.78]}/><meshBasicMaterial map={texture} toneMapped={false}/></mesh>)}
    <Html center position={[0,2.5,0]} style={{display:selected||showLabels?"block":"none"}}><button className={`map-label ${selected?"selected":""}`} data-world-object={object.id} onClick={onSelect}>{object.text}<small style={{display:"block",fontSize:9}}>{advertisement?"Advertisement":"Signboard"}</small></button></Html>
  </group>;
}

export default function WorldObjects({objects,selected,onSelect,showLabels}:{objects:PlacedWorldObject[];selected:string;onSelect:(id:string)=>void;showLabels:boolean}) {
  return <group name="resident-world-objects">{objects.filter(object=>object.status==="installed").map(object=><Signboard key={object.id} object={object} selected={selected===object.id} onSelect={()=>onSelect(object.id)} showLabels={showLabels}/>)}</group>;
}
