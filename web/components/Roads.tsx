"use client";
import {useEffect,useRef} from "react";
import * as THREE from "three";
import {layoutRoads,layoutBlocks,prototypeLayout,type CityLayout} from "@/lib/city-layout";
import OrganicRoads from "./OrganicRoads";

export default function Roads({material,layout=prototypeLayout}:{material:THREE.Material;layout?:CityLayout}) {
  const xRoads=layoutRoads(layout,true),zRoads=layoutRoads(layout,false),xBlocks=layoutBlocks(layout,true),zBlocks=layoutBlocks(layout,false);
  const walks=useRef<THREE.InstancedMesh>(null);
  const markings=useRef<THREE.InstancedMesh>(null);
  const count=xRoads.length*xBlocks.length*4;
  useEffect(()=>{
    const mesh=walks.current;if(!mesh)return;
    const t=new THREE.Object3D();let index=0;
    for(let r=0;r<xRoads.length;r++)for(let b=0;b<xBlocks.length;b++)for(const offset of [-2.4,2.4]) {
      t.position.set(xBlocks[b],.035,zRoads[r]+offset);t.rotation.y=0;t.updateMatrix();mesh.setMatrixAt(index++,t.matrix);
      t.position.set(xRoads[r]+offset,.035,zBlocks[b]);t.rotation.y=Math.PI/2;t.updateMatrix();mesh.setMatrixAt(index++,t.matrix);
    }
    mesh.instanceMatrix.needsUpdate=true;mesh.computeBoundingSphere();
    const lines=markings.current;if(!lines)return;index=0;
    for(let r=0;r<xRoads.length;r++)for(let b=0;b<xBlocks.length;b++) {
      t.position.set(xBlocks[b],.11,zRoads[r]);t.rotation.y=0;t.updateMatrix();lines.setMatrixAt(index++,t.matrix);
      t.position.set(xRoads[r],.11,zBlocks[b]);t.rotation.y=Math.PI/2;t.updateMatrix();lines.setMatrixAt(index++,t.matrix);
    }
    lines.instanceMatrix.needsUpdate=true;lines.computeBoundingSphere();
  },[layout]);
  if(layout.version>=2)return <OrganicRoads material={material}/>;
  return <>
    {xRoads.map((pos,i)=><group key={pos}>
      <mesh receiveShadow material={material} position={[pos,.04,layout.centerZ]}><boxGeometry args={[3.2,.1,11*layout.spacing]}/></mesh>
      <mesh receiveShadow material={material} position={[layout.centerX,.045,zRoads[i]]}><boxGeometry args={[11*layout.spacing,.1,3.2]}/></mesh>
    </group>)}
    <instancedMesh ref={walks} args={[undefined,undefined,count]} receiveShadow>
      <boxGeometry args={[layout.spacing-3.2,.08,.65]}/><meshStandardMaterial color="#b4baa9" roughness={1}/>
    </instancedMesh>
    <instancedMesh ref={markings} args={[undefined,undefined,count/2]}>
      <boxGeometry args={[1.6,.012,.08]}/><meshStandardMaterial color="#c5bd98"/>
    </instancedMesh>
  </>;
}
