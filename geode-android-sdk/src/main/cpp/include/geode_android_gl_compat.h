#pragma once
/*
 * Opt-in helpers for desktop renderer ports targeting Android GLES 3.1+.
 * Include after GLES3/gl31.h. These helpers do not intercept the game's GL.
 */
#include <GLES3/gl31.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#ifdef __cplusplus
extern "C" {
#endif
/* 'indirect' is a byte offset in the bound GL_DRAW_INDIRECT_BUFFER.
   GLES single-draw calls preserve the desktop multi-draw command order. */
static inline void geode_android_gl_multi_draw_arrays_indirect(GLenum mode,const void* indirect,GLsizei draw_count,GLsizei stride) {
 if(draw_count<=0) return;
 const uintptr_t base=(uintptr_t)indirect;
 const size_t step=stride>0?(size_t)stride:4u*sizeof(uint32_t);
 for(GLsizei i=0;i<draw_count;++i)
  glDrawArraysIndirect(mode,(const void*)(base+(uintptr_t)i*step));
}
static inline void geode_android_gl_multi_draw_elements_indirect(GLenum mode,GLenum type,const void* indirect,GLsizei draw_count,GLsizei stride) {
 if(draw_count<=0) return;
 const uintptr_t base=(uintptr_t)indirect;
 const size_t step=stride>0?(size_t)stride:5u*sizeof(uint32_t);
 for(GLsizei i=0;i<draw_count;++i)
  glDrawElementsIndirect(mode,type,(const void*)(base+(uintptr_t)i*step));
}
/* Desktop glMapBuffer equivalent; pair its result with glUnmapBuffer. */
static inline void* geode_android_gl_map_buffer(GLenum target,GLenum access) {
 GLint size=0;
 glGetBufferParameteriv(target,GL_BUFFER_SIZE,&size);
 if(size<=0) return NULL;
 GLbitfield flags;
 if(access==0x88B8u) flags=GL_MAP_READ_BIT; /* GL_READ_ONLY */
 else if(access==0x88B9u) flags=GL_MAP_WRITE_BIT|GL_MAP_INVALIDATE_BUFFER_BIT; /* GL_WRITE_ONLY */
 else flags=GL_MAP_READ_BIT|GL_MAP_WRITE_BIT; /* GL_READ_WRITE */
 return glMapBufferRange(target,0,(GLsizeiptr)size,flags);
}
/* Desktop glGetBufferSubData equivalent. The target buffer must be bound. */
static inline int geode_android_gl_get_buffer_sub_data(GLenum target,GLintptr offset,GLsizeiptr size,void* destination) {
 if(size<0||(size>0&&destination==NULL)) return 0;
 if(size==0) return 1;
 void* source=glMapBufferRange(target,offset,size,GL_MAP_READ_BIT);
 if(source==NULL) return 0;
 memcpy(destination,source,(size_t)size);
 return glUnmapBuffer(target)==GL_TRUE?1:0;
}
#ifdef __cplusplus
}
#endif
