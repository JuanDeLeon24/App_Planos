/*
 * Puente nativo DWG -> DXF basado en LibreDWG (GPLv3).
 * Convierte el DWG original (que nunca se modifica) en un DXF ASCII temporal
 * que luego lee el parser DXF en streaming del núcleo Kotlin.
 */
#include <stdio.h>
#include <string.h>
#include <dwg.h>
#include "bits.h"
#include "out_dxf.h"

/* Devuelve 0 si OK; en error rellena msg. */
int cadfield_dwg_to_dxf(const char *in_path, const char *out_path, char *msg, size_t msg_len)
{
  Dwg_Data dwg;
  Bit_Chain dat;
  int error;

  memset(&dwg, 0, sizeof(Dwg_Data));
  memset(&dat, 0, sizeof(Bit_Chain));
  dwg.opts = 0; /* sin logs */

  error = dwg_read_file(in_path, &dwg);
  if (error >= DWG_ERR_CRITICAL) {
    snprintf(msg, msg_len, "No se pudo leer el DWG (código 0x%x). Versión no soportada o archivo dañado.", error);
    dwg_free(&dwg);
    return error ? error : -1;
  }

  dat.version = dwg.header.version;
  dat.from_version = dwg.header.from_version;
  dat.codepage = dwg.header.codepage;
  dat.fh = fopen(out_path, "wb");
  if (!dat.fh) {
    snprintf(msg, msg_len, "No se pudo crear el DXF temporal");
    dwg_free(&dwg);
    return -2;
  }
  error = dwg_write_dxf(&dat, &dwg);
  fclose(dat.fh);
  dwg_free(&dwg);
  if (error >= DWG_ERR_CRITICAL) {
    snprintf(msg, msg_len, "Error al convertir el DWG a DXF (código 0x%x)", error);
    return error;
  }
  return 0;
}

#ifdef __ANDROID__
#include <jni.h>

JNIEXPORT jstring JNICALL
Java_com_cadfield_viewer_cad_LibreDwg_nativeDwgToDxf(JNIEnv *env, jclass cls, jstring jin, jstring jout)
{
  char msg[256] = {0};
  const char *in = (*env)->GetStringUTFChars(env, jin, NULL);
  const char *out = (*env)->GetStringUTFChars(env, jout, NULL);
  int rc = cadfield_dwg_to_dxf(in, out, msg, sizeof(msg));
  (*env)->ReleaseStringUTFChars(env, jin, in);
  (*env)->ReleaseStringUTFChars(env, jout, out);
  return rc == 0 ? NULL : (*env)->NewStringUTF(env, msg);
}

JNIEXPORT jboolean JNICALL
Java_com_cadfield_viewer_cad_LibreDwg_nativeAvailable(JNIEnv *env, jclass cls) { return JNI_TRUE; }
#endif
