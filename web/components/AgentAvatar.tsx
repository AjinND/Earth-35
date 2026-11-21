"use client";
import {useEffect,useLayoutEffect,useMemo,useRef,useState} from "react";
import {Canvas,useFrame} from "@react-three/fiber";
import {Html} from "@react-three/drei";
import * as THREE from "three";
import type {Agent} from "@/lib/types";
import {avatarPerformance,avatarStyle,performanceHeading,type AvatarMotion} from "@/lib/avatar";
import type {Seat} from "@/lib/gathering-furniture";
import {walkElevation} from "@/lib/island";
import {ResidentPlayback,residentFacingHeading} from "@/lib/resident-playback";
import type {PlaybackTimeline} from "@/lib/playback-timeline";

const taskLabel=(task:string)=>/^[A-Z_]+$/.test(task)?task.toLowerCase().replaceAll("_"," "):task;

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
export function PlushRig({id,motion,paused=false,raincoatWorn=false,seated=false,posture="standing",heldItem=null}:{id:string;motion:AvatarMotion;paused?:boolean;raincoatWorn?:boolean;seated?:boolean;posture?:string;heldItem?:string|null}) {
  const s=useMemo(()=>avatarStyle(id),[id]);const reduced=useReducedMotion();
  const book=heldItem==="book",tool=heldItem==="construction-tool"||heldItem==="hammer",meal=heldItem==="meal",coffee=heldItem==="coffee";
  const root=useRef<THREE.Group>(null),head=useRef<THREE.Group>(null),leftArm=useRef<THREE.Group>(null),rightArm=useRef<THREE.Group>(null),leftLeg=useRef<THREE.Group>(null),rightLeg=useRef<THREE.Group>(null),eyes=useRef<THREE.Group>(null);
  const elapsed=useRef(0);useEffect(()=>{elapsed.current=0;},[motion]);
  useFrame((_,delta)=>{
    if(!root.current||!head.current||!leftArm.current||!rightArm.current||!leftLeg.current||!rightLeg.current||!eyes.current)return;
    if(!paused&&!reduced)elapsed.current+=Math.min(delta,.05);
    const t=elapsed.current,animate=!paused&&!reduced,walk=animate&&(motion==="Walk"||motion==="Run"),run=motion==="Run";
    const phase=t*(run?13:8),stride=walk?Math.sin(phase)*(run?.95:.55):0;
    const sleep=motion==="Sleep"||posture==="lying",read=motion==="Read",rest=seated||posture==="seated",search=motion==="Search",observe=motion==="Observe",activity=motion==="Activity",jump=animate&&motion==="Jump"&&t<1.15;
    const hop=jump?Math.sin(Math.PI*Math.min(1,Math.max(0,(t-.15)/.85)))*1.3:0;
    root.current.position.y=sleep?.43:rest?-.22:hop+(walk?Math.abs(Math.sin(phase))*.08:animate?Math.sin(t*2)*.025:0);
    root.current.rotation.set(sleep?-Math.PI/2:run&&walk?.12:0,0,0);
    root.current.scale.set(1,jump&&t<.15?.87:1,1);
    head.current.rotation.set(read?.25:search?.2:0,animate&&(search||observe)?Math.sin(t*1.2)*(search?.2:.32):0,sleep?.1:animate?Math.sin(t*1.4)*.045:0);
    leftLeg.current.rotation.x=rest?-1.2:stride;rightLeg.current.rotation.x=rest?-1.2:-stride;
    leftArm.current.rotation.set(-stride,0,jump?-.9:rest?-.3:-.12);
    rightArm.current.rotation.set(stride,0,jump?.9:rest?.3:.12);
    if(animate&&motion==="Wave")rightArm.current.rotation.z=2.3+Math.sin(t*8)*.3;
    if(motion==="Work"){head.current.rotation.x=.12;leftArm.current.rotation.x=-.65;rightArm.current.rotation.x=-.68+(animate?Math.sin(t*2.1)*.08:0);}
    if(motion==="Build"){root.current.rotation.x=.1;head.current.rotation.x=.14;leftArm.current.rotation.x=-.7;rightArm.current.rotation.x=-.88+(animate?Math.sin(t*3.8)*.24:0);}
    if(motion==="Assemble"){head.current.rotation.x=.24;leftArm.current.rotation.x=-.8+(animate?Math.sin(t*1.8)*.06:0);rightArm.current.rotation.x=-.85+(animate?Math.sin(t*2.8)*.12:0);}
    if(motion==="Produce"){head.current.rotation.x=.18;leftArm.current.rotation.x=-.7+(animate?Math.sin(t*1.7)*.12:0);rightArm.current.rotation.x=-.7-(animate?Math.sin(t*1.7)*.12:0);}
    if(motion==="Install"){root.current.rotation.x=.2;head.current.rotation.x=.2;leftArm.current.rotation.x=-.95;rightArm.current.rotation.x=-1.05+(animate?Math.sin(t*2.2)*.1:0);}
    if(motion==="Research"){head.current.rotation.x=.18;head.current.rotation.y=animate?Math.sin(t*.7)*.14:0;leftArm.current.rotation.x=-.6;rightArm.current.rotation.x=-.75+(animate?Math.sin(t*1.5)*.055:0);}
    if(motion==="Talk"){head.current.rotation.x=animate?Math.sin(t*1.6)*.04:0;rightArm.current.rotation.set(-.25+(animate?Math.sin(t*2.2)*.12:0),0,.24);}
    if(motion==="Listen")head.current.rotation.x=.04+(animate?Math.max(0,Math.sin(t*1.1))*.05:0);
    if(motion==="Eat"){leftArm.current.rotation.x=-.9;rightArm.current.rotation.x=-1.2-(animate?(Math.sin(t*1.9)+1)*.3:0);head.current.rotation.x=.12;}
    if(motion==="Drink"){rightArm.current.rotation.x=-1.3-(animate?(Math.sin(t*1.2)+1)*.28:0);head.current.rotation.x=-.06;}
    if(search){root.current.rotation.x=.12+(animate?Math.sin(t*1.6)*.025:0);leftArm.current.rotation.x=-.2;rightArm.current.rotation.x=-.2;}
    if(activity){head.current.rotation.x=.06+(animate?Math.sin(t*1.3)*.04:0);rightArm.current.rotation.x=-.15+(animate?Math.sin(t*1.8)*.08:0);}
    if(read){leftArm.current.rotation.x=-.85;rightArm.current.rotation.x=-.85;}
    eyes.current.scale.y=sleep?.12:animate&&((t+s.seed%7)%4.6)>4.43?.12:1;
  });
  return <group ref={root} name={`plush-${id}`} userData={{motion,posture,heldItem}}>
    {raincoatWorn&&<group name={`raincoat-${id}`}><mesh castShadow position={[0,1.05,0]}><cylinderGeometry args={[.58,.66,1.05,16]}/><meshStandardMaterial color="#e6b742" roughness={.65}/></mesh><mesh position={[0,1.06,.64]}><boxGeometry args={[.025,.85,.025]}/><meshStandardMaterial color="#8d7024"/></mesh></group>}
    <group position={[0,1.02,0]}><Puff color={s.coat} scale={[.56,.65,.46]}/><Ball position={[0,-.05,.4]} scale={[.37,.43,.13]} color="#fff2d8"/><Ball position={[0,-.25,-.48]} scale={[.27,.27,.27]} color={s.coat}/></group>
    <group ref={leftLeg} position={[-.3,.43,0]}><Ball position={[0,-.17,.08]} scale={[.24,.28,.34]} color={s.coat}/></group>
    <group ref={rightLeg} position={[.3,.43,0]}><Ball position={[0,-.17,.08]} scale={[.24,.28,.34]} color={s.coat}/></group>
    <group ref={leftArm} position={[-.56,1.3,0]}><Ball position={[0,-.28,0]} scale={[.22,.38,.23]} color={s.coat}/></group>
    <group ref={rightArm} position={[.56,1.3,0]}><Ball position={[0,-.28,0]} scale={[.22,.38,.23]} color={s.coat}/>{tool&&<group position={[0,-.5,.15]} name={`construction-tool-${id}`}><mesh position={[0,.13,.08]}><boxGeometry args={[.08,.5,.08]}/><meshStandardMaterial color="#a47b4f"/></mesh><mesh position={[0,.38,.08]}><boxGeometry args={[.4,.14,.16]}/><meshStandardMaterial color="#788681"/></mesh></group>}{coffee&&<group position={[0,-.46,.12]} name={`coffee-cup-${id}`}><mesh><cylinderGeometry args={[.13,.11,.23,12]}/><meshStandardMaterial color="#eee5d3"/></mesh><mesh position={[.16,0,0]} rotation={[Math.PI/2,0,0]}><torusGeometry args={[.085,.025,5,12]}/><meshStandardMaterial color="#eee5d3"/></mesh></group>}{meal&&<group position={[0,-.45,.13]} name={`meal-${id}`}><mesh><sphereGeometry args={[.12,10,8]}/><meshStandardMaterial color="#cb9c64"/></mesh></group>}</group>
    <mesh position={[0,1.57,0]} rotation={[Math.PI/2,0,0]}><torusGeometry args={[.4,.13,8,20]}/><meshStandardMaterial color={s.accent} roughness={1}/></mesh>
    {book&&<group name={`reading-book-${id}`} position={[0,1.05,.67]} rotation={[-.18,0,0]}><mesh><boxGeometry args={[.7,.07,.45]}/><meshStandardMaterial color="#796752"/></mesh><mesh position={[0,.04,0]}><boxGeometry args={[.65,.025,.4]}/><meshStandardMaterial color="#f9efd8"/></mesh><mesh position={[0,.059,0]}><boxGeometry args={[.012,.01,.4]}/><meshStandardMaterial color="#c7bca4"/></mesh></group>}
    <Ball position={[.32,1.29,.43]} scale={[.13,.31,.09]} color={s.accent}/>
    <group ref={head} position={[0,2.03,0]}>
      {tool&&<group name={`construction-helmet-${id}`} position={[0,.42,0]}><mesh><sphereGeometry args={[.68,16,8,0,Math.PI*2,0,Math.PI/2]}/><meshStandardMaterial color="#e8b440"/></mesh><mesh position={[0,.01,0]}><cylinderGeometry args={[.77,.77,.07,16]}/><meshStandardMaterial color="#e8b440"/></mesh></group>}
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
  const physicalSeat=a.performance?a.performance.posture==="seated"&&a.performance.phase!=="approach"?seat:undefined:seat;
  const standingHeading=useRef((avatarStyle(a.id).seed%360)*Math.PI/180);
  const navigationHeading=useRef(physicalSeat?.rotation??standingHeading.current);
  const targetX=a.performance?.anchored?a.x:physicalSeat?.x??a.x,targetZ=a.performance?.anchored?a.z:physicalSeat?.z??a.z;
  const initial=useRef<[number,number,number]>([targetX,walkElevation(targetX,targetZ)+(physicalSeat?.y??.22),targetZ]);
  const logical=useRef({x:targetX,z:targetZ});
  const [moving,setMoving]=useState(false),[renderedActivity,setRenderedActivity]=useState(a.activity),[renderedSeat,setRenderedSeat]=useState(physicalSeat),[renderedProgress,setRenderedProgress]=useState(a.activityProgress),[renderedPerformance,setRenderedPerformance]=useState(a.performance);
  const playback=useRef(new ResidentPlayback(timeline));
  const [visible,setVisible]=useState(a.activity!=="RIDE_BUS");
  useLayoutEffect(()=>{playback.current.ingest(minute,{...a,x:targetX,z:targetZ,seat:physicalSeat},tickMs,performance.now());},[minute,a,tickMs,targetX,targetZ,physicalSeat]);
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
    if(sample.activityProgress!==renderedProgress)setRenderedProgress(sample.activityProgress);
    if(sample.performance!==renderedPerformance)setRenderedPerformance(sample.performance);
    if(sampledSeat!==renderedSeat)setRenderedSeat(sampledSeat);
    if(Math.hypot(dx,dz)>.0001)navigationHeading.current=Math.atan2(dx,dz);
    if(sampledSeat&&!active)navigationHeading.current=sampledSeat.rotation;
    const attending=typeof a.facingHeading==="number"&&Number.isFinite(a.facingHeading)&&typeof a.facingUntil==="number"&&a.facingUntil>0&&minute<a.facingUntil;
    const taskHeading=performanceHeading(sample.performance,active);
    const angle=taskHeading??residentFacingHeading(sample,navigationHeading.current,standingHeading.current,attending?a.facingHeading!:undefined);
    facing.current.rotation.y+=Math.atan2(Math.sin(angle-facing.current.rotation.y),Math.cos(angle-facing.current.rotation.y))*Math.min(1,delta*10);
    logical.current={x,z};p.set(x,walkElevation(x,z)+(sampledSeat&&!active?sampledSeat.y:.22),z);
    root.current.userData.seated=!!sampledSeat&&!active;
    root.current.userData.moving=active;
    root.current.userData.transit=["WAIT_BUS","BOARD_BUS","RIDE_BUS"].includes(sample.activity);
    root.current.userData.routeAnchored=sample.routeAnchored;
    root.current.userData.performanceAnchored=!!sample.performance?.anchored;
    root.current.userData.performancePhase=sample.performance?.phase??null;
    root.current.userData.activity=sample.activity;
  },-2);
  const {motion,posture,heldItem}=avatarPerformance(renderedActivity,renderedProgress,renderedPerformance,moving,!!renderedSeat,constructionWorking);
  const activityCaption=renderedProgress?moving&&(renderedProgress.phase==="travel"||renderedProgress.phase==="blocked")?renderedProgress.mode==="walk"?"walking":"exploring":renderedProgress.phase==="blocked"?"activity blocked":renderedProgress.phase==="travel"?"waiting to move":renderedProgress.mode==="search"?"inspecting":renderedProgress.mode==="observe"?"observing":"spending time":undefined;
  return <group ref={root} name={`resident-${a.id}`} position={initial.current} onClick={e=>{e.stopPropagation();onSelect();}}>
    <group ref={facing} rotation={[0,physicalSeat?.rotation??standingHeading.current,0]} scale={.45}><PlushRig id={a.id} motion={motion} posture={posture} heldItem={heldItem} seated={!!renderedSeat&&!moving} paused={a.status==="paused"||renderedPerformance?.phase==="paused"} raincoatWorn={a.raincoatWorn}/></group>
    <mesh rotation={[-Math.PI/2,0,0]} position={[0,renderedSeat&&!moving?.23-renderedSeat.y:.01,0]}><ringGeometry args={[selected?.43:.3,selected?.54:.35,32]}/><meshBasicMaterial color={selected?"#dff986":"#f8f4e8"}/></mesh>
    {selected&&!indoors&&visible&&<Html position={[0,1.8,0]} center style={{pointerEvents:"none"}}><span className="map-label selected" data-avatar-motion={motion} data-activity-phase={renderedProgress?.phase} data-performance-phase={renderedPerformance?.phase} data-posture={posture} data-held-item={heldItem??""} title={renderedPerformance?.task?taskLabel(renderedPerformance.task):renderedProgress?.label} data-seated={posture==="seated"} data-raincoat-worn={!!a.raincoatWorn}>{a.name}{renderedPerformance?` · ${renderedPerformance.phase==="blocked"?"waiting":renderedPerformance.phase==="paused"?"paused":taskLabel(renderedPerformance.task)}`:activityCaption?` · ${activityCaption}`:renderedActivity==="READ_BOOK"?" · reading":renderedActivity==="SLEEP"?" · zzz":renderedActivity==="CHAT"?" · chatting":renderedActivity==="BUILD_CONSTRUCTION"&&constructionWorking?" · building":renderedActivity==="PLACE_OBJECT"?" · installing a sign":renderedActivity==="MOVE_OBJECT"?" · moving a sign":""}</span></Html>}
  </group>;
}
export function AvatarPreview({agent,constructionWorking=true}:{agent:Agent;constructionWorking?:boolean}) {
  const [preview,setPreview]=useState<AvatarMotion|null>(null);
  useEffect(()=>setPreview(null),[agent.id]);
  const progress=agent.activity==="DO_ACTIVITY"?agent.activityProgress:null;
  const live=avatarPerformance(agent.activity,progress,agent.performance,!agent.performance&&["MOVE","RUN","BOARD_BUS"].includes(agent.activity),agent.seatIndex!=null,constructionWorking),motion=preview??live.motion;
  const heldItem=preview?preview==="Read"?"book":preview==="Build"?"construction-tool":preview==="Eat"?"meal":preview==="Drink"?"coffee":null:live.heldItem;
  const posture=preview?preview==="Rest"?"seated":preview==="Sleep"?"lying":"standing":live.posture;
  return <div className="avatar-preview"><div className="avatar-preview-stage" data-motion={motion} data-performance-phase={agent.performance?.phase} data-posture={posture} data-held-item={heldItem??""} data-raincoat-worn={!!agent.raincoatWorn}>
    <Canvas camera={{position:[0,3,7.5],fov:36}} onCreated={({camera})=>camera.lookAt(0,1.65,0)} dpr={[1,1.5]} aria-label={`${agent.name}'s fluffy avatar: ${motion.toLowerCase()}`}><color attach="background" args={["#eeeede"]}/><ambientLight intensity={1.8}/><directionalLight position={[3,5,4]} intensity={2.5}/><group position={[0,-.25,0]} rotation={[0,-.2,0]}><PlushRig id={agent.id} motion={motion} posture={posture} heldItem={heldItem} paused={!preview&&(agent.status==="paused"||agent.performance?.phase==="paused")} raincoatWorn={agent.raincoatWorn}/></group></Canvas>
  </div><label>Avatar preview<select aria-label="Avatar animation preview" value={preview??"Live"} onChange={e=>setPreview(e.target.value==="Live"?null:e.target.value as AvatarMotion)}><option value="Live">Live activity</option>{(["Idle","Walk","Run","Jump","Rest","Sleep","Work","Build","Wave","Search","Observe","Activity","Read","Research","Assemble","Produce","Install","Listen","Talk","Eat","Drink"] as AvatarMotion[]).map(m=><option key={m}>{m}</option>)}</select></label>{preview&&<small>Animation preview · agent activity is unchanged</small>}{!preview&&agent.performance&&<p className="activity-progress" data-performance-phase={agent.performance.phase}><strong>{taskLabel(agent.performance.task)}</strong><br/>{agent.performance.phase==="approach"?"Moving into position":agent.performance.phase==="blocked"?"Waiting; no work credited":agent.performance.phase==="paused"?"Paused":`${agent.performance.activeMinutes} active ${agent.performance.activeMinutes===1?"minute":"minutes"}`}</p>}{progress&&<p className="activity-progress" data-activity-phase={progress.phase}><strong>{progress.label}</strong><br/>{progress.phase==="blocked"?"Waiting to resume; no progress recorded":progress.phase==="travel"?"Moving through the area":progress.phase==="inspect"?"Inspecting surroundings":progress.mode==="observe"?"Observing surroundings":"Time at this location only; no physical task is recorded"}<br/>{progress.activeMinutes} / {progress.requestedMinutes} active minutes<br/>{progress.distance.toFixed(2)} world units travelled · {progress.inspectedStops} inspection stops<progress aria-label="Activity progress" value={progress.activeMinutes} max={Math.max(1,progress.requestedMinutes)}/></p>}</div>;
}

