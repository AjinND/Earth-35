"use client";
import {useEffect,useLayoutEffect,useMemo,useRef,useState} from "react";
import {Canvas,useFrame} from "@react-three/fiber";
import {Html} from "@react-three/drei";
import * as THREE from "three";
import type {Agent} from "@/lib/types";
import {avatarMotion,avatarStyle,type AvatarMotion} from "@/lib/avatar";
import type {Seat} from "@/lib/gathering-furniture";
import {walkElevation} from "@/lib/island";
import {ResidentPlayback} from "@/lib/resident-playback";
import type {PlaybackTimeline} from "@/lib/playback-timeline";

function useReducedMotion() {
  const [reduced,setReduced]=useState(false);
  useEffect(()=>{const query=matchMedia("(prefers-reduced-motion: reduce)");const update=()=>setReduced(query.matches);update();query.addEventListener("change",update);return()=>query.removeEventListener("change",update);},[]);
  return reduced;
}
function Puff({color,scale=[1,1,1]}:{color:string;scale?:[number,number,number]}) {
  const ref=useRef<THREE.InstancedMesh>(null);
  useEffect(()=>{
    const transform=new THREE.Object3D();
    for(let i=0;i<42;i++) {
      const y=1-2*(i+.5)/42,r=Math.sqrt(1-y*y),angle=i*2.399963;
      transform.position.set(Math.cos(angle)*r*.84,y*.84,Math.sin(angle)*r*.84);
      transform.scale.setScalar(.29+.035*Math.sin(i*5));transform.updateMatrix();ref.current!.setMatrixAt(i,transform.matrix);
    }
    ref.current!.instanceMatrix.needsUpdate=true;ref.current!.computeBoundingSphere();
  },[]);
  return <group scale={scale}><mesh castShadow><sphereGeometry args={[.88,16,12]}/><meshStandardMaterial color={color} roughness={1}/></mesh><instancedMesh ref={ref} args={[undefined,undefined,42]} castShadow><sphereGeometry args={[1,8,6]}/><meshStandardMaterial color={color} roughness={1}/></instancedMesh></group>;
}
function Ball({position,scale,color}:{position:[number,number,number];scale:[number,number,number];color:string}) {
  return <mesh position={position} scale={scale} castShadow><sphereGeometry args={[1,12,10]}/><meshStandardMaterial color={color} roughness={.95}/></mesh>;
}
export function PlushRig({id,motion,paused=false,raincoatWorn=false}:{id:string;motion:AvatarMotion;paused?:boolean;raincoatWorn?:boolean}) {
  const s=useMemo(()=>avatarStyle(id),[id]);const reduced=useReducedMotion();
  const root=useRef<THREE.Group>(null),head=useRef<THREE.Group>(null),leftArm=useRef<THREE.Group>(null),rightArm=useRef<THREE.Group>(null),leftLeg=useRef<THREE.Group>(null),rightLeg=useRef<THREE.Group>(null),eyes=useRef<THREE.Group>(null);
  const elapsed=useRef(0);useEffect(()=>{elapsed.current=0;},[motion]);
  useFrame((_,delta)=>{
    if(!root.current||!head.current||!leftArm.current||!rightArm.current||!leftLeg.current||!rightLeg.current||!eyes.current)return;
    if(!paused&&!reduced)elapsed.current+=Math.min(delta,.05);
    const t=elapsed.current,animate=!paused&&!reduced,walk=animate&&(motion==="Walk"||motion==="Run"),run=motion==="Run";
    const phase=t*(run?13:8),stride=walk?Math.sin(phase)*(run?.95:.55):0;
    const sleep=motion==="Sleep",rest=motion==="Rest",jump=animate&&motion==="Jump"&&t<1.15;
    const hop=jump?Math.sin(Math.PI*Math.min(1,Math.max(0,(t-.15)/.85)))*1.3:0;
    root.current.position.y=sleep?.43:rest?-.22:hop+(walk?Math.abs(Math.sin(phase))*.08:animate?Math.sin(t*2)*.025:0);
    root.current.rotation.set(sleep?-Math.PI/2:run&&walk?.12:0,0,0);
    root.current.scale.set(1,jump&&t<.15?.87:1,1);
    head.current.rotation.z=sleep?.1:animate?Math.sin(t*1.4)*.045:0;
    leftLeg.current.rotation.x=rest?-1.2:stride;rightLeg.current.rotation.x=rest?-1.2:-stride;
    leftArm.current.rotation.set(-stride,0,jump?-.9:rest?-.3:-.12);
    rightArm.current.rotation.set(stride,0,jump?.9:rest?.3:.12);
    if(animate&&motion==="Wave")rightArm.current.rotation.z=2.3+Math.sin(t*8)*.3;
    if(animate&&motion==="Work"){leftArm.current.rotation.x=-.8+Math.sin(t*5)*.18;rightArm.current.rotation.x=-.8-Math.sin(t*5)*.18;}
    if(motion==="Build"){root.current.rotation.x=.1;leftArm.current.rotation.x=-.7;rightArm.current.rotation.x=-.9+(animate?Math.sin(t*5)*.6:0);}
    eyes.current.scale.y=sleep?.12:animate&&((t+s.seed%7)%4.6)>4.43?.12:1;
  });
  return <group ref={root} name={`plush-${id}`}>
    {raincoatWorn&&<group name={`raincoat-${id}`}><mesh castShadow position={[0,1.05,0]}><cylinderGeometry args={[.58,.66,1.05,16]}/><meshStandardMaterial color="#e6b742" roughness={.65}/></mesh><mesh position={[0,1.06,.64]}><boxGeometry args={[.025,.85,.025]}/><meshStandardMaterial color="#8d7024"/></mesh></group>}
    <group position={[0,1.02,0]}><Puff color={s.coat} scale={[.56,.65,.46]}/><Ball position={[0,-.05,.4]} scale={[.37,.43,.13]} color="#fff2d8"/><Ball position={[0,-.25,-.48]} scale={[.27,.27,.27]} color={s.coat}/></group>
    <group ref={leftLeg} position={[-.3,.43,0]}><Ball position={[0,-.17,.08]} scale={[.24,.28,.34]} color={s.coat}/></group>
    <group ref={rightLeg} position={[.3,.43,0]}><Ball position={[0,-.17,.08]} scale={[.24,.28,.34]} color={s.coat}/></group>
    <group ref={leftArm} position={[-.56,1.3,0]}><Ball position={[0,-.28,0]} scale={[.22,.38,.23]} color={s.coat}/></group>
    <group ref={rightArm} position={[.56,1.3,0]}><Ball position={[0,-.28,0]} scale={[.22,.38,.23]} color={s.coat}/>{motion==="Build"&&<group position={[0,-.5,.15]} name={`construction-tool-${id}`}><mesh position={[0,.13,.08]}><boxGeometry args={[.08,.5,.08]}/><meshStandardMaterial color="#a47b4f"/></mesh><mesh position={[0,.38,.08]}><boxGeometry args={[.4,.14,.16]}/><meshStandardMaterial color="#788681"/></mesh></group>}</group>
    <mesh position={[0,1.57,0]} rotation={[Math.PI/2,0,0]}><torusGeometry args={[.4,.13,8,20]}/><meshStandardMaterial color={s.accent} roughness={1}/></mesh>
    <Ball position={[.32,1.29,.43]} scale={[.13,.31,.09]} color={s.accent}/>
    <group ref={head} position={[0,2.03,0]}>
      {motion==="Build"&&<group name={`construction-helmet-${id}`} position={[0,.42,0]}><mesh><sphereGeometry args={[.68,16,8,0,Math.PI*2,0,Math.PI/2]}/><meshStandardMaterial color="#e8b440"/></mesh><mesh position={[0,.01,0]}><cylinderGeometry args={[.77,.77,.07,16]}/><meshStandardMaterial color="#e8b440"/></mesh></group>}
      <group position={[-.47,.52,0]} rotation={[0,0,.18]}><Puff color={s.coat} scale={[.23,s.longEars?.51:.25,.2]}/><Ball position={[0,.04,.16]} scale={[.11,s.longEars?.3:.13,.045]} color="#e4afab"/></group>
      <group position={[.47,.52,0]} rotation={[0,0,-.18]}><Puff color={s.coat} scale={[.23,s.longEars?.51:.25,.2]}/><Ball position={[0,.04,.16]} scale={[.11,s.longEars?.3:.13,.045]} color="#e4afab"/></group>
      <Puff color={s.coat} scale={[.67,.57,.52]}/>
      <Ball position={[0,-.16,.47]} scale={[.33,.22,.16]} color="#fff2d8"/>
      <Ball position={[0,-.08,.626]} scale={[.095,.064,.048]} color="#3c3935"/>
      <group ref={eyes} position={[0,.06,.49]}>{[-1,1].map(side=><group key={side} position={[side*.235,0,0]}><Ball position={[0,0,0]} scale={[.064,.083,.045]} color="#29332f"/><Ball position={[-.016,.024,.038]} scale={[.019,.023,.012]} color="#ffffff"/></group>)}</group>
      <Ball position={[-.42,-.11,.41]} scale={[.09,.043,.032]} color="#dfaa9c"/><Ball position={[.42,-.11,.41]} scale={[.09,.043,.032]} color="#dfaa9c"/>
    </group>
  </group>;
}
export default function AgentAvatar({agent:a,selected,onSelect,tickMs,minute,indoors=false,seat,timeline,constructionWorking=false}:{agent:Agent;selected:boolean;onSelect:()=>void;tickMs:number;minute:number;indoors?:boolean;seat?:Seat;timeline?:PlaybackTimeline;constructionWorking?:boolean}) {
  const root=useRef<THREE.Group>(null),facing=useRef<THREE.Group>(null);
  const navigationHeading=useRef(seat?.rotation??0);
  const targetX=seat?.x??a.x,targetZ=seat?.z??a.z;
  const initial=useRef<[number,number,number]>([targetX,walkElevation(targetX,targetZ)+(seat?.y??.22),targetZ]);
  const logical=useRef({x:targetX,z:targetZ});
  const [moving,setMoving]=useState(false),[renderedActivity,setRenderedActivity]=useState(a.activity),[renderedSeat,setRenderedSeat]=useState(seat);
  const playback=useRef(new ResidentPlayback(timeline));
  const [visible,setVisible]=useState(a.activity!=="RIDE_BUS");
  useLayoutEffect(()=>{playback.current.ingest(minute,{...a,x:targetX,z:targetZ,seat},tickMs,performance.now());},[minute,a,tickMs,targetX,targetZ,seat]);
  useFrame((_,delta)=>{
    if(!root.current||!facing.current)return;
    const p=root.current.position;
    const sample=playback.current.sample(performance.now(),matchMedia("(prefers-reduced-motion: reduce)").matches);
    const {x,z,seat:sampledSeat}=sample,dx=x-logical.current.x,dz=z-logical.current.z;
    const shown=sample.visible;
    root.current.visible=shown;if(shown!==visible)setVisible(shown);
    const active=shown&&(sample.moving||sample.activity==="BOARD_BUS");
    if(active!==moving)setMoving(active);
    if(sample.activity!==renderedActivity)setRenderedActivity(sample.activity);
    if(sampledSeat!==renderedSeat)setRenderedSeat(sampledSeat);
    if(Math.hypot(dx,dz)>.0001)navigationHeading.current=Math.atan2(dx,dz);
    if(sampledSeat&&!active)navigationHeading.current=sampledSeat.rotation;
    const attending=typeof a.facingHeading==="number"&&Number.isFinite(a.facingHeading)&&typeof a.facingUntil==="number"&&a.facingUntil>0&&minute<a.facingUntil;
    const angle=attending?a.facingHeading!:navigationHeading.current;
    facing.current.rotation.y+=Math.atan2(Math.sin(angle-facing.current.rotation.y),Math.cos(angle-facing.current.rotation.y))*Math.min(1,delta*10);
    logical.current={x,z};p.set(x,walkElevation(x,z)+(sampledSeat&&!active?sampledSeat.y:.22),z);
    root.current.userData.seated=!!sampledSeat&&!active;
    root.current.userData.transit=["WAIT_BUS","BOARD_BUS","RIDE_BUS"].includes(sample.activity);
    root.current.userData.activity=sample.activity;
  },-2);
  const motion=moving?(renderedActivity==="RUN"?"Run":"Walk"):renderedSeat?"Rest":avatarMotion(renderedActivity==="MOVE"||renderedActivity==="RUN"||renderedActivity==="BUILD_CONSTRUCTION"&&!constructionWorking?"Idle":renderedActivity);
  return <group ref={root} name={`resident-${a.id}`} position={initial.current} onClick={e=>{e.stopPropagation();onSelect();}}>
    <group ref={facing} rotation={[0,seat?.rotation??0,0]} scale={.45}><PlushRig id={a.id} motion={motion} paused={a.status==="paused"} raincoatWorn={a.raincoatWorn}/></group>
    <mesh rotation={[-Math.PI/2,0,0]} position={[0,renderedSeat&&!moving?.23-renderedSeat.y:.01,0]}><ringGeometry args={[selected?.43:.3,selected?.54:.35,32]}/><meshBasicMaterial color={selected?"#dff986":"#f8f4e8"}/></mesh>
    {selected&&!indoors&&visible&&<Html position={[0,1.8,0]} center style={{pointerEvents:"none"}}><span className="map-label selected" data-avatar-motion={motion} data-seated={!!renderedSeat&&!moving} data-raincoat-worn={!!a.raincoatWorn}>{a.name}{renderedActivity==="SLEEP"?" · zzz":renderedActivity==="CHAT"?" · chatting":renderedActivity==="BUILD_CONSTRUCTION"&&constructionWorking?" · building":renderedActivity==="PLACE_OBJECT"?" · installing a sign":renderedActivity==="MOVE_OBJECT"?" · moving a sign":""}</span></Html>}
  </group>;
}
export function AvatarPreview({agent,constructionWorking=true}:{agent:Agent;constructionWorking?:boolean}) {
  const [preview,setPreview]=useState<AvatarMotion|null>(null);
  useEffect(()=>setPreview(null),[agent.id]);
  const motion=preview??avatarMotion(agent.activity==="BUILD_CONSTRUCTION"&&!constructionWorking?"Idle":agent.activity);
  return <div className="avatar-preview"><div className="avatar-preview-stage" data-motion={motion} data-raincoat-worn={!!agent.raincoatWorn}>
    <Canvas camera={{position:[0,3,7.5],fov:36}} onCreated={({camera})=>camera.lookAt(0,1.65,0)} dpr={[1,1.5]} aria-label={`${agent.name}'s fluffy avatar: ${motion.toLowerCase()}`}><color attach="background" args={["#eeeede"]}/><ambientLight intensity={1.8}/><directionalLight position={[3,5,4]} intensity={2.5}/><group position={[0,-.25,0]} rotation={[0,-.2,0]}><PlushRig id={agent.id} motion={motion} raincoatWorn={agent.raincoatWorn}/></group></Canvas>
  </div><label>Avatar preview<select aria-label="Avatar animation preview" value={preview??"Live"} onChange={e=>setPreview(e.target.value==="Live"?null:e.target.value as AvatarMotion)}><option value="Live">Live activity</option>{(["Idle","Walk","Run","Jump","Rest","Sleep","Work","Build","Wave"] as AvatarMotion[]).map(m=><option key={m}>{m}</option>)}</select></label>{preview&&<small>Animation preview · agent activity is unchanged</small>}</div>;
}

