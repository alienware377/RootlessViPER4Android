/*
 * JNI face of the SOFA reader, for hrtf/SofaReader.kt. Only the isolated
 * helper process loads this library.
 */
#include <jni.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include "sofa_extract.h"

/* Reads the whole descriptor, at most SOFA_MAX_BYTES + 1 so an oversized
   file is seen as such. Closes it. */
static char *readAll(int fd, size_t *size)
{
	size_t cap = 1 << 20, n = 0;
	struct stat st;
	if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0)
		cap = (size_t)st.st_size + 1;
	if (cap > SOFA_MAX_BYTES + 1) cap = SOFA_MAX_BYTES + 1;
	char *buf = (char *)malloc(cap);
	if (!buf) { close(fd); return NULL; }
	for (;;)
	{
		if (n == cap)
		{
			if (cap > SOFA_MAX_BYTES) break;
			size_t grown = cap * 2;
			if (grown > SOFA_MAX_BYTES + 1) grown = SOFA_MAX_BYTES + 1;
			char *b = (char *)realloc(buf, grown);
			if (!b) { free(buf); close(fd); return NULL; }
			buf = b;
			cap = grown;
		}
		const ssize_t r = read(fd, buf + n, cap - n);
		if (r < 0)
		{
			if (errno == EINTR) continue;
			free(buf);
			close(fd);
			return NULL;
		}
		if (r == 0) break;
		n += (size_t)r;
	}
	close(fd);
	*size = n;
	return buf;
}

/*
 * info:    [error, libError, taps, measurements, diffBins, swapped]
 * meta:    [fs, then azimuth and elevation of each of the nine pairs]
 * strings: [title, licence, database name]
 * Returns the nine pairs followed by the diffuse power average, or null.
 */
JNIEXPORT jfloatArray JNICALL
Java_me_timschneeberger_rootlessjamesdsp_hrtf_SofaReader_nativeExtract(JNIEnv *env, jclass cls,
	jint fd, jintArray info, jfloatArray meta, jobjectArray strings)
{
	(void)cls;
	jint inf[6] = { SOFA_ERR_SIZE, 0, 0, 0, 0, 0 };
	size_t size = 0;
	char *data = readAll(fd, &size);
	SofaSet set;
	memset(&set, 0, sizeof(set));
	if (!data)
		set.error = SOFA_ERR_SIZE;
	else
	{
		sofaExtract(data, size, &set);
		free(data);
	}
	inf[0] = set.error;
	inf[1] = set.libError;
	jfloatArray result = NULL;
	if (set.error == SOFA_OK)
	{
		inf[2] = set.taps;
		inf[3] = set.measurements;
		inf[4] = set.diffBins;
		inf[5] = set.swapped;
		jfloat m[1 + SOFA_SPEAKERS * 2];
		m[0] = set.fs;
		memcpy(m + 1, set.dirs, sizeof(set.dirs));
		(*env)->SetFloatArrayRegion(env, meta, 0, 1 + SOFA_SPEAKERS * 2, m);
		const jsize nh = (jsize)(SOFA_SPEAKERS * 2 * set.taps);
		result = (*env)->NewFloatArray(env, nh + set.diffBins);
		if (result)
		{
			(*env)->SetFloatArrayRegion(env, result, 0, nh, set.hrir);
			(*env)->SetFloatArrayRegion(env, result, nh, set.diffBins, set.diffuse);
		}
		const char *txt[3] = { set.title, set.license, set.database };
		for (int i = 0; i < 3; i++)
		{
			/* NewStringUTF wants modified UTF-8; anything else becomes '?'. */
			char clean[1024];
			size_t j = 0;
			for (const unsigned char *p = (const unsigned char *)txt[i]; *p && j < sizeof(clean) - 1; p++)
				clean[j++] = *p < 128 ? (char)*p : '?';
			clean[j] = 0;
			jstring s = (*env)->NewStringUTF(env, clean);
			(*env)->SetObjectArrayElement(env, strings, i, s);
		}
		sofaFree(&set);
	}
	(*env)->SetIntArrayRegion(env, info, 0, 6, inf);
	return result;
}
