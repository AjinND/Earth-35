"use client";
import {useEffect,useRef,useState,type RefObject} from "react";
import {useFrame} from "@react-three/fiber";
import {Volume2,VolumeX} from "lucide-react";
import {Vector3} from "three";
import type {World} from "@/lib/types";
import {soundscapeAt} from "@/lib/soundscape";
import {WorldAudio} from "@/lib/world-audio";
import {readStormFrame,thunderAt} from "@/lib/storm";

const volumeKey="earth35-world-volume";
const defaultVolume=.35;

export function useWorldSound() {
  const engine=useRef<WorldAudio|null>(null),mounted=useRef(false),intent=useRef(false),transitions=useRef(0);
  const [enabled,setEnabled]=useState(false),[busy,setBusy]=useState(false),[available,setAvailable]=useState(true);
  const [volume,setVolume]=useState(defaultVolume),[error,setError]=useState("");
  useEffect(()=>{
    mounted.current=true;setAvailable(WorldAudio.supported());
    try {
      const saved=localStorage.getItem(volumeKey),value=Number(saved);
      if(saved!==null&&Number.isFinite(value)&&value>=0&&value<=1)setVolume(value);
    }catch{/* Storage may be disabled; sound still works for this visit. */}
    const visibility=async()=>{
      const current=engine.current;if(!current)return;
      transitions.current++;
      try {
        if(document.hidden||!intent.current)await current.suspend();
        else {
          await current.resume();
          if(!mounted.current||engine.current!==current)return;
          if(document.hidden||!intent.current)await current.suspend();
          else if(current.state!=="running")throw new Error("Audio remains suspended");
          else setEnabled(true);
        }
      }catch{
        if(!mounted.current||engine.current!==current)return;
        intent.current=false;setEnabled(false);setError("Tap Sound to resume listening.");
      }finally{transitions.current--;}
    };
    document.addEventListener("visibilitychange",visibility);
    return()=>{
      mounted.current=false;intent.current=false;
      document.removeEventListener("visibilitychange",visibility);
      engine.current?.dispose();engine.current=null;
    };
  },[]);

  const toggle=async()=>{
    if(busy||transitions.current>0||!available)return;
    setBusy(true);setError("");
    transitions.current++;
    const next=!intent.current;intent.current=next;
    if(!next)setEnabled(false);
    try {
      // Create and resume synchronously from this explicit click, before awaiting.
      const current=engine.current??(engine.current=new WorldAudio());
      current.onStateChange=state=>{
        if(!mounted.current||engine.current!==current||document.hidden||transitions.current>0)return;
        if(state!=="running"&&intent.current) {
          intent.current=false;setEnabled(false);setError("Tap Sound to resume listening.");
          void current.suspend().catch(()=>current.dispose());
        }else if(state==="running"&&!intent.current) {
          // Device recovery must not restart playback after the control says off.
          void current.suspend().catch(()=>current.dispose());
        }
      };
      if(next)await current.resume();else await current.suspend();
      if(!mounted.current||engine.current!==current)return;
      if(next&&intent.current&&!document.hidden) {
        if(current.state!=="running")throw new Error("Audio remains suspended");
        setEnabled(true);
      }else if(document.hidden)await current.suspend();
    }catch{
      if(!mounted.current)return;
      intent.current=false;setEnabled(false);setError("Sound could not start. Tap Sound to try again.");
      engine.current?.dispose();engine.current=null;
    }finally{transitions.current--;if(mounted.current)setBusy(false);}
  };
  const changeVolume=(value:number)=>{
    const next=Math.max(0,Math.min(1,value));setVolume(next);
    try{localStorage.setItem(volumeKey,String(next));}catch{/* Optional preference. */}
  };
  return {engine,enabled,busy,available,volume,error,toggle,changeVolume};
}

export function WorldSoundControls({sound}:{sound:ReturnType<typeof useWorldSound>}) {
  const Icon=sound.enabled?Volume2:VolumeX;
  return <div className="world-sound" role="group" aria-label="World sound controls" data-sound-enabled={sound.enabled}>
    <button aria-label={sound.enabled?"Mute world sound":"Enable world sound"} aria-pressed={sound.enabled} disabled={sound.busy||!sound.available} onClick={sound.toggle} title={sound.available?"Listen to the island":"Sound is unavailable in this browser"}><Icon size={17}/><span>{sound.busy?"Starting…":sound.enabled?"Sound on":"Sound off"}</span></button>
    {sound.enabled&&<label><span className="sr-only">World sound volume</span><input type="range" min="0" max="100" step="5" value={Math.round(sound.volume*100)} onChange={event=>sound.changeVolume(Number(event.target.value)/100)}/></label>}
    {sound.error&&<p role="status">{sound.error}</p>}
  </div>;
}

export function WorldSoundListener({world,engine,volume}:{world:World;engine:RefObject<WorldAudio|null>;volume:number}) {
  const elapsed=useRef(1),direction=useRef(new Vector3()),lastStrike=useRef<number|null>(null);
  useFrame(({camera,gl},delta)=>{
    const current=engine.current;
    if(world.weather!=="Rain")current?.cancelThunder();
    const storm=readStormFrame(gl.domElement);
    if(storm?.strike&&lastStrike.current!==storm.strike.id) {
      // Consume silent/hidden strikes too; enabling sound never replays an old rumble.
      lastStrike.current=storm.strike.id;
      if(!document.hidden&&storm.age<.65&&current?.state==="running") {
        camera.getWorldDirection(direction.current);
        const sound=thunderAt(storm.strike,{x:camera.position.x,z:camera.position.z,yaw:Math.atan2(-direction.current.x,-direction.current.z)},storm.age);
        if(current.playThunder(sound))Object.assign(gl.domElement.dataset,{thunderId:String(storm.strike.id),thunderDelay:sound.delay.toFixed(3),thunderPan:sound.pan.toFixed(3)});
      }
    }
    gl.domElement.dataset.thunderVoices=String(current?.pendingThunder??0);
    elapsed.current+=delta;if(elapsed.current<.25)return;elapsed.current=0;
    gl.domElement.dataset.soundState=current?.state??"off";
    if(!current||current.state!=="running")return;
    camera.getWorldDirection(direction.current);
    const mix=soundscapeAt({x:camera.position.x,z:camera.position.z,yaw:Math.atan2(-direction.current.x,-direction.current.z)},world.minute,world.weather,(world.geography?.version??0)>=2);
    current.update(mix,volume);
    Object.assign(gl.domElement.dataset,{soundRain:mix.rain.toFixed(3),soundSurf:mix.surf.gain.toFixed(3),soundRiver:mix.river.gain.toFixed(3),soundWaterfall:mix.waterfall.gain.toFixed(3),soundBirds:mix.birds.gain.toFixed(3),soundInsects:mix.insects.toFixed(3)});
  });
  return null;
}
