"use client";
import {useEffect,useMemo,useRef} from "react";
import {useFrame} from "@react-three/fiber";
import * as THREE from "three";
import {FALLS,LANDSCAPES} from "@/lib/landscapes";
import {riverLevel} from "@/lib/island";
import {riverSample} from "@/lib/river-geometry";
import {atInlet,boulderGeometry,waterfallCliffGeometry,mistParticle,sprayParticle,MIST_COUNT,SPRAY_COUNT,INLET_MIST_COUNT,INLET_SPRAY_COUNT,WATERFALL_DROP,WATERFALL_ROCKS} from "@/lib/waterfall";

const mistShader={
  uniforms:THREE.UniformsUtils.merge([THREE.UniformsLib.fog]),
  vertexShader:`attribute float puffOpacity;
    varying vec2 vUv; varying float vOpacity;
    #include <fog_pars_vertex>
    void main() {
      vUv=uv; vOpacity=puffOpacity;
      vec4 mvPosition=modelViewMatrix*instanceMatrix*vec4(position,1.0);
      gl_Position=projectionMatrix*mvPosition;
      #include <fog_vertex>
    }`,
  fragmentShader:`varying vec2 vUv; varying float vOpacity;
    #include <fog_pars_fragment>
    void main() {
      float radius=length(vUv-.5)*2.0;
      float alpha=exp(-radius*radius*4.0)*(1.0-smoothstep(.4,1.0,radius))*vOpacity*.24;
      if(alpha<.002) discard;
      gl_FragColor=vec4(.87,.95,.94,alpha);
      #include <tonemapping_fragment>
      #include <colorspace_fragment>
      #include <fog_fragment>
    }`,
  transparent:true,depthWrite:false,fog:true,
};

export default function WaterfallEffects() {
  const rocks=useRef<THREE.InstancedMesh>(null),spray=useRef<THREE.InstancedMesh>(null),mist=useRef<THREE.InstancedMesh>(null),foam=useRef<THREE.InstancedMesh>(null),streaks=useRef<THREE.InstancedMesh>(null);
  const elapsed=useRef(0),report=useRef(0),motion=useRef(false),dummy=useMemo(()=>new THREE.Object3D(),[]);
  const geometry=useMemo(()=>{
    const mist=new THREE.PlaneGeometry(2,2);mist.setAttribute("puffOpacity",new THREE.InstancedBufferAttribute(new Float32Array(MIST_COUNT+INLET_MIST_COUNT),1));
    return {rock:boulderGeometry(),cliff:waterfallCliffGeometry(),mist};
  },[]);
  useEffect(()=>{
    const query=matchMedia("(prefers-reduced-motion: reduce)"),update=()=>{motion.current=!query.matches;};update();query.addEventListener("change",update);
    return()=>{query.removeEventListener("change",update);Object.values(geometry).forEach(g=>g.dispose());};
  },[geometry]);
  useEffect(()=>{
    if(!rocks.current)return;const color=new THREE.Color();
    WATERFALL_ROCKS.forEach((rock,i)=>{
      dummy.position.set(rock.x,rock.y,rock.z);dummy.rotation.set(.13*Math.sin(i),rock.angle,.15*Math.cos(i));dummy.scale.set(rock.sx,rock.sy,rock.sz);dummy.updateMatrix();
      rocks.current!.setMatrixAt(i,dummy.matrix);color.set(i%5===0?"#74806a":i%3===0?"#879187":"#66736c");rocks.current!.setColorAt(i,color);
    });
    rocks.current.instanceMatrix.needsUpdate=true;rocks.current.computeBoundingSphere();
  },[dummy]);
  useFrame(({camera,gl},delta)=>{
    if(motion.current)elapsed.current+=Math.min(delta,.1);
    const time=elapsed.current,base=riverLevel(LANDSCAPES.lakeRiverEnd+1);
    if(spray.current)for(let i=0;i<SPRAY_COUNT+INLET_SPRAY_COUNT;i++) {
      const source=sprayParticle(i,time),particle=i<SPRAY_COUNT?source:atInlet(source);dummy.position.set(particle.x,particle.y,particle.z);dummy.rotation.set(0,0,0);
      const size=particle.size*(.25+.75*particle.opacity)*(i<SPRAY_COUNT?1:.7);dummy.scale.set(size,size*1.6,size);dummy.updateMatrix();spray.current.setMatrixAt(i,dummy.matrix);
    }
    if(mist.current)for(let i=0;i<MIST_COUNT+INLET_MIST_COUNT;i++) {
      const source=mistParticle(i,time),particle=i<MIST_COUNT?source:atInlet(source);dummy.position.set(particle.x,particle.y,particle.z);dummy.quaternion.copy(camera.quaternion);
      dummy.scale.setScalar(particle.size*(i<MIST_COUNT?1:.65));dummy.updateMatrix();mist.current.setMatrixAt(i,dummy.matrix);
      (geometry.mist.getAttribute("puffOpacity") as THREE.InstancedBufferAttribute).setX(i,particle.opacity);
    }
    if(foam.current)for(let i=0;i<36;i++) {
      const phase=(time*.26+i*.137)%1,angle=i*2.399,radius=.25+phase*2.1;
      const source={x:FALLS.x+FALLS.dx*(.75+Math.cos(angle)*radius*.32)+FALLS.dz*Math.sin(angle)*radius,
        y:base+.065,z:FALLS.z+FALLS.dz*(.75+Math.cos(angle)*radius*.32)-FALLS.dx*Math.sin(angle)*radius};
      const particle=i<24?source:atInlet(source);dummy.position.set(particle.x,particle.y,particle.z);
      dummy.rotation.set(-Math.PI/2,0,angle);dummy.scale.set(.2+phase*.5,.13+phase*.26,1);dummy.updateMatrix();foam.current.setMatrixAt(i,dummy.matrix);
    }
    if(streaks.current)for(let i=0;i<36;i++) {
      const phase=(time*.8+i*.173)%1,p=riverSample(LANDSCAPES.lakeRiverEnd,.48+phase*.04),across=Math.sin(i*3.13)*p.width*.42;
      dummy.position.set(p.x+p.sx*across+FALLS.dx*.035,p.y+.035,p.z+p.sz*across+FALLS.dz*.035);
      dummy.rotation.set(0,Math.atan2(FALLS.dx,FALLS.dz),0);dummy.scale.set(1,.8+(i%4)*.32,1);dummy.updateMatrix();streaks.current.setMatrixAt(i,dummy.matrix);
    }
    (geometry.mist.getAttribute("puffOpacity") as THREE.InstancedBufferAttribute).needsUpdate=true;
    for(const mesh of [spray.current,mist.current,foam.current,streaks.current])if(mesh){mesh.instanceMatrix.needsUpdate=true;mesh.computeBoundingSphere();}
    report.current+=delta;if(report.current>.25) {
      report.current=0;gl.domElement.dataset.landscapeVersion=String(LANDSCAPES.version);gl.domElement.dataset.waterfallFlow=time.toFixed(3);
      gl.domElement.dataset.waterfallDrop=WATERFALL_DROP.toFixed(2);
    }
  });
  return <group name="silverstream-rocks-and-impact">
    <mesh name="waterfall-solid-granite-cliff" geometry={geometry.cliff} receiveShadow castShadow><meshStandardMaterial vertexColors flatShading roughness={1} side={THREE.DoubleSide}/></mesh>
    <instancedMesh name="waterfall-rock-formations" ref={rocks} geometry={geometry.rock} args={[undefined,undefined,WATERFALL_ROCKS.length]} castShadow receiveShadow><meshStandardMaterial flatShading roughness={1}/></instancedMesh>
    <instancedMesh name="falling-water-streaks" ref={streaks} args={[undefined,undefined,36]} raycast={()=>null}><boxGeometry args={[.06,1,.045]}/><meshBasicMaterial color="#e4f4f0" transparent opacity={.6}/></instancedMesh>
    <instancedMesh name="waterfall-splash-droplets" ref={spray} args={[undefined,undefined,SPRAY_COUNT+INLET_SPRAY_COUNT]} raycast={()=>null}><icosahedronGeometry args={[1,0]}/><meshBasicMaterial color="#e5f5f2" transparent opacity={.75}/></instancedMesh>
    <instancedMesh name="waterfall-airborne-mist" ref={mist} geometry={geometry.mist} args={[undefined,undefined,MIST_COUNT+INLET_MIST_COUNT]} raycast={()=>null}><shaderMaterial {...mistShader}/></instancedMesh>
    <instancedMesh name="plunge-pool-foam" ref={foam} args={[undefined,undefined,36]} raycast={()=>null}><circleGeometry args={[1,7]}/><meshBasicMaterial color="#dbefea" transparent opacity={.55} depthWrite={false} side={THREE.DoubleSide}/></instancedMesh>
  </group>;
}
