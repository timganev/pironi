PIRONI PORTABLE FOR WINDOWS 11
==============================

Не са нужни admin права, Maven или инсталирана Java.

1. Разархивирай ZIP файла в папка, в която имаш право да пишеш,
   например Documents\Pironi.

2. Отвори Command Prompt в разархивираната папка.

3. Задай API ключа само за текущия прозорец:

   set DEEPSEEK_API_KEY=твоят-ключ

4. Стартирай Pironi:

   pironi.bat --provider deepseek --model deepseek-v4-flash --context 131072 --max-output-tokens 16384 --max-turns 30 --activity auto

Можеш да подадеш друга workspace папка с:

   --workspace "C:\Users\ТВОЕТО-ИМЕ\Documents\project"

Pironi създава последната workspace папка, ако още не съществува. Кавичките са
задължителни, когато пътят съдържа интервали.

Не мести pironi.bat, pironi.jar или runtime поотделно. Те трябва да останат
заедно в разархивираната папка.
