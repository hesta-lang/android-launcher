#include "geode_android_graphics.h"
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
namespace {
bool at_least(int a,int b,int x,int y){return a>x||(a==x&&b>=y);}
bool parse_version(const char* text,int& major,int& minor){
 if(!text)return false;
 const char* p=std::strstr(text,"OpenGL ES");
 p=p? p+std::strlen("OpenGL ES"):text;
 while(*p&&!std::isdigit(static_cast<unsigned char>(*p)))++p;
 if(!*p)return false;
 char* end=nullptr; long ma=std::strtol(p,&end,10);
 if(end==p||*end!='.')return false;
 const char* m=end+1; long mi=std::strtol(m,&end,10);
 if(end==m||ma<0||mi<0)return false;
 major=(int)ma;minor=(int)mi;return true;
}
bool legacy_ext(const char* all,const char* ext){
 if(!all||!ext||!*ext||std::strchr(ext,' '))return false;
 size_t n=std::strlen(ext);const char* p=all;
 while((p=std::strstr(p,ext))!=nullptr){
  if((p==all||p[-1]==' ')&&(p[n]=='\0'||p[n]==' '))return true;
  p+=n;
 }return false;
}
bool has_ext(const char* ext,int major,int minor){
 if(!ext||!*ext)return false;
 if(at_least(major,minor,3,0)){
  GLint count=0;glGetIntegerv(GL_NUM_EXTENSIONS,&count);
  for(GLint i=0;i<count;++i){
   const char* value=(const char*)glGetStringi(GL_EXTENSIONS,(GLuint)i);
   if(value&&std::strcmp(value,ext)==0)return true;
  }return false;
 }
 return legacy_ext((const char*)glGetString(GL_EXTENSIONS),ext);
}
void copy_string(char* out,size_t cap,GLenum name){
 if(!out||!cap)return;const char* v=(const char*)glGetString(name);
 if(!v){out[0]='\0';return;}std::snprintf(out,cap,"%s",v);
}
}
extern "C" GEODE_ANDROID_GRAPHICS_EXPORT int32_t geode_android_graphics_query_current_context(geode_android_graphics_info* info){
 if(!info||info->struct_size<sizeof(uint32_t)*2u)return -1;
 const uint32_t caller_size=info->struct_size;
 geode_android_graphics_info result{};result.struct_size=sizeof(result);
 if(eglGetCurrentContext()==EGL_NO_CONTEXT){
  std::memcpy(info,&result,std::min<size_t>(caller_size,sizeof(result)));return 0;
 }
 result.flags|=GEODE_ANDROID_GRAPHICS_CONTEXT_CURRENT;
 copy_string(result.vendor,sizeof(result.vendor),GL_VENDOR);
 copy_string(result.renderer,sizeof(result.renderer),GL_RENDERER);
 copy_string(result.version,sizeof(result.version),GL_VERSION);
 copy_string(result.shading_language_version,sizeof(result.shading_language_version),GL_SHADING_LANGUAGE_VERSION);
 if(!parse_version(result.version,result.major_version,result.minor_version)){
  std::memcpy(info,&result,std::min<size_t>(caller_size,sizeof(result)));return 1;
 }
 const int ma=result.major_version,mi=result.minor_version;
 if(at_least(ma,mi,3,1)){
  result.flags|=GEODE_ANDROID_GRAPHICS_GLES31|GEODE_ANDROID_GRAPHICS_COMPUTE_SHADER|
   GEODE_ANDROID_GRAPHICS_SHADER_STORAGE_BUFFER|GEODE_ANDROID_GRAPHICS_DRAW_INDIRECT|
   GEODE_ANDROID_GRAPHICS_PROGRAM_RESOURCE_QUERY|GEODE_ANDROID_GRAPHICS_MAP_BUFFER_RANGE;
  glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS,&result.max_compute_work_group_invocations);
  glGetIntegerv(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS,&result.max_shader_storage_buffer_bindings);
  GLint64 max_block=0;glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE,&max_block);
  result.max_shader_storage_block_size=(int64_t)max_block;
  for(GLuint axis=0;axis<3;++axis){
   glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_COUNT,axis,&result.max_compute_work_group_count[axis]);
   glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_SIZE,axis,&result.max_compute_work_group_size[axis]);
  }
 }
 if(has_ext("GL_EXT_multi_draw_indirect",ma,mi))result.flags|=GEODE_ANDROID_GRAPHICS_MULTI_DRAW_INDIRECT;
 if(has_ext("GL_EXT_shader_draw_parameters",ma,mi))result.flags|=GEODE_ANDROID_GRAPHICS_SHADER_DRAW_PARAMETERS;
 if(at_least(ma,mi,3,2)||has_ext("GL_KHR_debug",ma,mi))result.flags|=GEODE_ANDROID_GRAPHICS_KHR_DEBUG;
 if(has_ext("GL_EXT_disjoint_timer_query",ma,mi)||has_ext("GL_EXT_disjoint_timer_query_webgl2",ma,mi))
  result.flags|=GEODE_ANDROID_GRAPHICS_DISJOINT_TIMER_QUERY;
 std::memcpy(info,&result,std::min<size_t>(caller_size,sizeof(result)));return 1;
}
extern "C" GEODE_ANDROID_GRAPHICS_EXPORT int32_t geode_android_graphics_has_extension(const char* ext){
 if(!ext||!*ext||eglGetCurrentContext()==EGL_NO_CONTEXT)return 0;
 int ma=0,mi=0;const char* v=(const char*)glGetString(GL_VERSION);
 return parse_version(v,ma,mi)&&has_ext(ext,ma,mi)?1:0;
}
extern "C" GEODE_ANDROID_GRAPHICS_EXPORT void* geode_android_graphics_get_proc_address(const char* name){
 if(!name||!*name||eglGetCurrentContext()==EGL_NO_CONTEXT)return nullptr;
 return reinterpret_cast<void*>(eglGetProcAddress(name));
}
