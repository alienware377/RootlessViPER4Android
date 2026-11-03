// Does the GraphicEQ text parser stay inside its node array, whatever it is fed?
//
//   J=app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp
//   $NDK/x86_64-linux-android29-clang -O2 -w -ffp-contract=off -I $J -I $J/generalDSP \
//       -Wl,--wrap=malloc tools/geqparse_harness.c <engine objects> \
//       tools/lockstub.c -lm -o tools/geqparse_x86
//
// The string is "GraphicEQ: f g; f g; ..." from the app, and in root mode from
// any app. The parser counts the numbers, allocates count / 2 nodes, then
// fills them in a second pass. With an odd count the stray last number was
// written as the frequency of nodes[count / 2], one past the end: found by
// tools/paramfuzz_harness.cpp under AddressSanitizer. Here every allocation
// is padded with a guard pattern, so the read past the end returns
// 0xA5A5A5A5A5A5A5A5 instead of whatever the heap held, and the write through
// it crashes deterministically, sanitizer or not.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "ArbFIRGen.h"

#define GUARD 64
void *__real_malloc(size_t n);
void *__wrap_malloc(size_t n)
{
	unsigned char *p = (unsigned char *)__real_malloc(n + GUARD);
	if (p) memset(p + n, 0xA5, GUARD);
	return p;
}

static int failures = 0;
static ArbitraryEq g_eq;

static void parse(const char *label, const char *text, unsigned int wantNodes,
	float wantFirstFreq, float wantLastGain)
{
	char buf[512];
	snprintf(buf, sizeof(buf), "%s", text);
	ArbitraryEqString2SortedNodes(&g_eq, buf);
	int ok = g_eq.nodesCount == wantNodes;
	if (ok && wantNodes)
		ok = g_eq.nodes[0]->freq == wantFirstFreq && g_eq.nodes[wantNodes - 1]->gain == wantLastGain;
	printf("  %-40s nodes %u  %s\n", label, g_eq.nodesCount, ok ? "ok" : "FAIL");
	if (!ok) failures++;
}

int main(void)
{
	printf("== GraphicEQ strings ==\n");
	parse("pairs", "GraphicEQ: 100 1; 1000 -2; 10000 3", 3, 100.0f, 3.0f);
	parse("one stray number at the end", "GraphicEQ: 100 1; 1000 -2; 10000", 2, 100.0f, -2.0f);
	parse("stray number, node count unchanged", "GraphicEQ: 50 4; 500 5; 5000", 2, 50.0f, 5.0f);
	parse("a single number", "GraphicEQ: 100", 0, 0.0f, 0.0f);
	parse("nothing", "GraphicEQ:", 0, 0.0f, 0.0f);
	parse("no prefix, pairs", "20 0; 20000 0", 2, 20.0f, 0.0f);
	parse("back to pairs after strays", "GraphicEQ: 30 1; 300 2; 3000 3; 30000 4", 4, 30.0f, 4.0f);
	parse("seven numbers", "GraphicEQ: 1 2 3 4 5 6 7", 3, 1.0f, 6.0f);
	EqNodesFree(&g_eq);
	printf("%s\n", failures ? "FAIL" : "PASS");
	return failures != 0;
}
