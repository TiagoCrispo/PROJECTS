from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
old = '''                Surface(\n                    modifier = Modifier\n                        .align(Alignment.TopCenter)\n                        .padding(horizontal = 12.dp, vertical = 8.dp),\n                    shape = RoundedCornerShape(18.dp),\n                    color = Color(0xB31A1A1A),\n                ) {\n                    Row(\n                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),\n                        verticalAlignment = Alignment.CenterVertically,\n                        horizontalArrangement = Arrangement.spacedBy(10.dp),\n                    ) {\n                        Text(\n                            text = \"Pellizca para ampliar · doble toque para zoom\",\n                            style = MaterialTheme.typography.bodySmall,\n                            color = Color.White,\n                        )\n                        TextButton(onClick = onClose) {\n                            Text(\"Cerrar\", color = Color.White)\n                        }\n                    }\n                }\n'''
new = '''                Surface(\n                    modifier = Modifier\n                        .align(Alignment.TopEnd)\n                        .padding(horizontal = 12.dp, vertical = 8.dp),\n                    shape = RoundedCornerShape(18.dp),\n                    color = Color(0xB31A1A1A),\n                ) {\n                    TextButton(onClick = onClose) {\n                        Text(\"Cerrar\", color = Color.White)\n                    }\n                }\n'''
if old not in text:
    raise SystemExit('top bar anchor not found')
text = text.replace(old, new, 1)
path.write_text(text)
print('v0.8.3 clean top bar applied', path)
