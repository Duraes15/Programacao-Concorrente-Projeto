<div style="text-align: center; margin-top: 100px;">

# Relatório de Trabalho Prático - Grupo 2
## Programação Concorrente

Universidade do Minho <br>
15 de Maio de 2026

<br><br><br>

### Membros do Grupo: 
Pedro Miguel Igreja Gomes - a102929 <br>
Fábio Mendes Castelhano - a105728 <br>
Joao Carlos Teixeira Neiva - a108579 <br>
João Álvaro Oliveira Durães - a109065

</div>

<div style="page-break-after: always;"></div>

## 1. Introdução
No âmbito do projeto da UC de Programação Concorrente, do 3º ano de LCC, este relatório tem como objetivo elucidar as principais decisões tomadas na arquitetura Cliente-Servidor do projeto.

De maneira suficientemente detalhada, o grupo tentará explicar as nuances:
1. Da implementação do cliente em Java, por exemplo quais bibliotecas foram utilizadas para tratar da interface gráfica, e qual foi a abordagem para tratar múltiplos clientes. Apesar de sugerida a utilização do procesing, o grupo optou por utilizar outras bibliotecas;
2. Da implementação do servidor em Erlang, nomeadamente quais funções que tratam de cada funcionalidade do jogo, como é feita a troca de informação entre o cliente e o servidor, e qual é o critério para haver troca de informações entre o cliente e o servidor.

## 2. Implementação do Cliente (Java)
O cliente foi desenvolvido para atuar como uma interface de visualização e captura de comandos, delegando toda a lógica física ao servidor. As bibliotecas utilizadas para a interface gráfica foram a javax.swing e a java.awt.

### 2.1. Funcionalidades e Estrutura de Funções
A execução do cliente baseia-se num fluxo de estados gerido pelas seguintes funções principais:
* **Inicialização e Interface:** A função `initGUI()` configura a janela principal em modo maximizado. A navegação entre os diferentes ecrãs (Login, Menu, Jogo, Rankings) é efetuada pelas funções `mudarParaLogin()`, `mudarParaMenu()`, `mudarParaJogo()` e `mudarParaRanking()`, que limpam e reconstroem o contentor principal (`JFrame`).
* **Comunicação de Rede:** A função `conectarServidor()` estabelece a ligação TCP e inicia uma *thread* dedicada para a leitura contínua de mensagens através da função `processarMensagem(String msg)`. Esta última atua como um despachante, interpretando comandos como `Sucesso`, `DATA` (atualização do mundo) ou `RANK_START/END`.
* **Interação e Input:** No `LoginPanel`, a função `enviar(String cmd)` trata do registo e autenticação. Durante a partida, o `GamePanel` utiliza um `KeyAdapter` para detetar teclas premidas e um `inputTimer` que, a cada 30ms, envia os comandos de movimento (`UP`, `LEFT`, `RIGHT`, `ESC`) para o servidor.
* **Renderização do Mundo:** A função `updateWorld(String msg)` processa as strings de dados do servidor, atualizando o mapa local. O desenho gráfico é realizado em `paintComponent(Graphics g)`, que percorre os objetos e jogadores para desenhar os círculos com cores, bordas e linhas de direção conforme as regras do enunciado.

### 2.2. Gestão de Múltiplos Clientes e Concorrência Interna
Embora o cliente seja individual, ele processa e exibe dados de múltiplos jogadores enviados pelo servidor:
* **Estado Local Partilhado:** Todos os jogadores e objetos são armazenados no `gameObjectsMap`. Para evitar conflitos de concorrência entre a *thread* de rede (que escreve os dados) e a *thread* de interface gráfica (que lê os dados para desenhar), este mapa é implementado como um `ConcurrentHashMap`.
* **Diferenciação do "Eu":** O código utiliza a variável `myUsername` para identificar qual dos jogadores recebidos na mensagem `DATA` corresponde ao utilizador local. Isso permite que a função `paintComponent` aplique a cor azul na borda do avatar do próprio jogador e vermelha nos restantes, conforme exigido.

## 3. Implementação do Servidor (Erlang)
O servidor é o motor central do jogo, responsável por manter a consistência do estado global e processar a física de todas as partidas em simultâneo.

### 3.1. Concorrência e Gestão de Clientes
O servidor tira partido do modelo de atores do Erlang para suportar múltiplos utilizadores:
* **Processos por Cliente:** A função `accept_loop/1` aceita novas conexões e faz o `spawn` de um processo individual `handle_client/1` para cada socket. Este processo gere o ciclo de vida do utilizador através de `client_loop/1` e `menu_loop/2`.
* **Armazenamento Partilhado (ETS):** Para que processos independentes acedam a dados comuns, são utilizadas tabelas ETS: `utilizadores` (registo), `rankings` (pontuações) e `sessoes_ativas` (para impedir logins duplicados).

### 3.2. Matchmaking e Simulação de Partida
* **Gestão de Filas:** O processo `match_maker_loop/2` recebe pedidos de entrada na fila e estados de prontidão (`READY`). A função `verificar_inicio_partida/2` monitoriza quando 3 ou 4 jogadores estão prontos, iniciando a simulação via `iniciar_jogo_com_n/3`.
* **Simulação de Física (`tick`):** A cada 30ms, o `partida_loop` executa uma sequência rigorosa:
    1. **Movimento:** `aplicar_movimento_global/1` calcula as novas posições considerando o atrito (`?ATRITO`) e os limites do mapa.
    2. **Colisões Geométricas:** `processar_colisoes/2` utiliza a distância euclidiana para detetar toques em venenos ou sobreposições em comestíveis.
    3. **Resolução de Capturas (PVP):** A função `processar_capturas_jogadores/1` ordena os jogadores por massa antes de processar os ataques, garantindo que o jogador com a maior massa tenha sempre prioridade sobre os jogadores com as menores massas.
* **Protocolo de Sincronização:** A função `broadcast_estado/2` serializa o estado complexo do Erlang numa string simplificada, a fim de facilitar o *parsing* no Java.

## 4. Conclusão
O desenvolvimento deste projeto demonstrou a eficácia da separação de responsabilidades entre um servidor robusto, seguindo o modelo de troca de mensagens, e um cliente visual. A utilização do Erlang revelou-se ideal para a gestão de múltiplas partidas e sessões concorrentes devido à leveza dos seus processos e facilidade de monitorização (`monitor/2`), que seria bem mais complexo de se fazer ao utilizar uma linguagem como o Java, por exemplo, que segue uma estratégia para tratar concorrência bem diferente.

Um dos maiores desafios técnicos superados foi a sincronização do estado visual com a lógica do servidor. A implementação da limpeza do mapa local no Java (`gameObjectsMap.clear()`) a cada atualização de rede foi fundamental para eliminar os ditos "objetos fantasma" e garantir que a interface refletisse fielmente as colisões processadas no servidor. 

Em suma, o sistema cumpre a maior parte dos requisitos do enunciado, desde a autenticação segura até à mecânica complexa de capturas e gestão dinâmica de massa, resultando numa aplicação multijogador minimamente funcional e escalável dentro dos limites propostos. 