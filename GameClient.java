import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.HashMap; // Adiciona este import
import java.util.HashSet;
import java.util.Map;     // Adiciona este import
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap; // Adiciona este import
import java.util.HashSet;
import java.util.Set;


public class GameClient extends JFrame {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    
    private LoginPanel loginPanel;
    private GamePanel gamePanel;
    private MenuPanel menuPanel;
    private RankingPanel rankingPanel;

    private StringBuilder rankBuffer = new StringBuilder();
    private boolean collectingRankings = false;

    // Substitui a variável playerPositions antiga por esta:
    private Map<String, GameObject> gameObjectsMap = new ConcurrentHashMap<>();

    private String myUsername; 

    public GameClient() {
        initGUI();
        SwingUtilities.invokeLater(() -> conectarServidor());
    }

    private void initGUI() {
        setTitle("Mini-Jogo Concorrente");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        setVisible(true);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        
        loginPanel = new LoginPanel();
        getContentPane().add(loginPanel);
        
        revalidate();
        repaint();
    }

    private void conectarServidor() {
        new Thread(() -> {
            try {
                socket = new Socket("127.0.0.1", 12345);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                String line;
                while ((line = in.readLine()) != null) {
                    processarMensagem(line);
                }
            } catch (IOException e) {
                System.out.println("Erro de conexão: " + e.getMessage());
            }
        }).start();
    }

    private void processarMensagem(String msg) {
        // 1. Lógica de Rankings (Captura de buffer)
        if (msg.equals("RANK_START")) {
            collectingRankings = true;
            rankBuffer.setLength(0);
            return;
        }
        if (msg.equals("RANK_END")) {
            collectingRankings = false;
            SwingUtilities.invokeLater(() -> mudarParaRanking(rankBuffer.toString()));
            return;
        }
        if (collectingRankings) {
            rankBuffer.append(msg).append("\n");
            return;
        }

        if (msg.contains("Sucesso: Ola")) {
            // Extrai o nome da mensagem "Sucesso: Ola Nome!"
            // this.myUsername = msg.replace("Sucesso: Ola ", "").replace("!", "").trim();
            // Comentei a linha acima, pois a coloquei de maneira diferente no método enviar,
            // guardei o username logo na função enviar, pois estava dando erro na hora de identificar
            // os jogadores com a borda azul ou vermelha. Se concordarem com a minha abordagem, acho que 
            // a linha de código acima não é mais necessária, pois estava causando problemas.
            SwingUtilities.invokeLater(() -> mudarParaMenu());
        } 
        else if (msg.contains("A partida vai comecar")) {
            SwingUtilities.invokeLater(() -> mudarParaJogo());
        }
        // 3. Atualização do Mundo (Onde a magia acontece)
        else if (msg.startsWith("DATA") && gamePanel != null) {
            gamePanel.updateWorld(msg);
        }
        else if (msg.contains("FIM")){
            if (gamePanel != null) {
                gamePanel.stopInput(); // Para o timer antes de mudar de ecrã
                gamePanel = null;
            }
            SwingUtilities.invokeLater(() -> mudarParaMenu());
        }
        // 4. Mensagens informativas (Fila de espera, erros, etc)
        else {
            SwingUtilities.invokeLater(() -> {
                if (menuPanel != null && menuPanel.isShowing()) {
                    menuPanel.setInfo(msg);
                } else if (loginPanel != null && loginPanel.isShowing()) {
                    loginPanel.setStatus(msg);
                }
            });
        }
    }

    private void mudarParaRanking(String dados) {
        getContentPane().removeAll();
        rankingPanel = new RankingPanel();
        rankingPanel.setTexto(dados);
        add(rankingPanel);
        revalidate();
        repaint();
    }

    private void mudarParaMenu() {
        getContentPane().removeAll();
        menuPanel = new MenuPanel();
        add(menuPanel);
        revalidate();
        repaint();
    }

    private void mudarParaJogo() {
        getContentPane().removeAll();
        gamePanel = new GamePanel();
        add(gamePanel);
        revalidate();
        repaint();
    }
    
    private void mudarParaLogin() {
        getContentPane().removeAll();
        loginPanel = new LoginPanel();
        add(loginPanel);
        revalidate();
        repaint();
    }

    // --- SUB-ECRÃS (Classes Internas) ---

    class LoginPanel extends JPanel {
        JTextField userField = new JTextField(10);
        JPasswordField passField = new JPasswordField(10);
        JButton loginBtn = new JButton("Login");
        JButton registerBtn = new JButton("Registar");
        JButton cancelBtn = new JButton("Apagar Conta");
        JLabel statusLabel = new JLabel("Bem-vindo!");

        public LoginPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            JPanel f = new JPanel();
            f.add(new JLabel("User:")); f.add(userField);
            f.add(new JLabel("Pass:")); f.add(passField);
            JPanel b = new JPanel();
            b.add(loginBtn); b.add(registerBtn); b.add(cancelBtn);
            add(f); add(b); add(statusLabel);

            loginBtn.addActionListener(e -> enviar("login"));
            registerBtn.addActionListener(e -> enviar("register"));
            cancelBtn.addActionListener(e -> enviar("cancel"));
        }

        private void enviar(String cmd) {
            // Se o comando for login ou register, guardamos logo o username limpo
            if (cmd.equals("login") || cmd.equals("register")) {
                GameClient.this.myUsername = userField.getText().trim();
            }
            
            out.println(cmd + "," + userField.getText() + "," + new String(passField.getPassword()));
        }

        public void setStatus(String msg) { statusLabel.setText(msg); }
    }

class MenuPanel extends JPanel {
        JButton playBtn = new JButton("Procurar Partida");
        JButton readyBtn = new JButton("ESTOU PRONTO");
        JButton rankBtn = new JButton("Ver Rankings");
        JButton logoutBtn = new JButton("Sair/Logout");
        JLabel infoLabel = new JLabel("Escolhe uma opção para continuar.", SwingConstants.CENTER);

        public MenuPanel() {
            setLayout(new GridLayout(5, 1, 10, 10));
            setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));
            infoLabel.setFont(new Font("Arial", Font.BOLD, 14));

            readyBtn.setVisible(false);
            readyBtn.setBackground(Color.ORANGE);

            add(infoLabel);
            add(playBtn);
            add(readyBtn);
            add(rankBtn);
            add(logoutBtn);

            playBtn.addActionListener(e -> {
                out.println("1");
                playBtn.setEnabled(false);
                readyBtn.setVisible(true);
                infoLabel.setText("Estás na fila. Clica em 'Estou Pronto' para jogar.");
            });

            readyBtn.addActionListener(e -> {
                out.println("READY");
                readyBtn.setEnabled(false);
                readyBtn.setText("AGUARDANDO...");
            });

            rankBtn.addActionListener(e -> {
                out.println("2");
                infoLabel.setText("A carregar rankings...");
            });

            logoutBtn.addActionListener(e -> {
                out.println("3");
                mudarParaLogin();
            });
        }
        
        public void setInfo(String txt) {
            String formattedTxt = "<html><body style='text-align:center;'>" + 
                                  txt.replace("\n", "<br>") + 
                                  "</body></html>";
            infoLabel.setText(formattedTxt);
        }
    }

    // Dentro da classe GameClient, mas fora de outros métodos
    class GameObject {
        String type; // "P" para Player, "O" para Objeto
        String id;
        double x, y, angulo, massa;
        int objectType; // 1 para comestível, 2 para venenoso
        int score;

        // Construtor para Jogador
        GameObject(String id, double x, double y, double angulo, double massa, int score) {
            this.type = "P"; this.id = id; this.x = x; this.y = y; this.angulo = angulo; this.massa = massa;this.score = score;
        }
        // Construtor para Objeto
        GameObject(String id, double x, double y, int objType, double tam) {
            this.type = "O"; this.id = id; this.x = x; this.y = y; this.objectType = objType; this.massa = tam;
        }
    }

    // PROBLEMA: updateWorld chama repaint() que agenda paint na EDT.
// O socket reader (thread separada) chama updateWorld, mas se a EDT
// estiver ocupada com paint, a mailbox do socket acumula e cria lag.
//
// FIX: Usar volatile + estratégia "last-write-wins" para o estado do mundo.
// O socket reader apenas escreve o último estado; o timer de render lê-o.
// Isto desacopla completamente I/O de rendering — nunca bloqueiam um ao outro.

class GamePanel extends JPanel {
    // volatile garante visibilidade entre threads sem synchronized
    private volatile String lastWorldState = null;
    private final Set<Integer> keysPressed = new HashSet<>();
    private Timer inputTimer;
    private Timer renderTimer;  // ← NOVO: timer dedicado ao render

    public GamePanel() {
        setFocusable(true);
        requestFocusInWindow();

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e)  { keysPressed.add(e.getKeyCode()); }
            @Override public void keyReleased(KeyEvent e) { keysPressed.remove(e.getKeyCode()); }
        });

        // Timer de input: envia comandos ao servidor (~33 fps)
        inputTimer = new Timer(30, e -> {
            if (keysPressed.contains(KeyEvent.VK_UP))    out.println("UP");
            if (keysPressed.contains(KeyEvent.VK_LEFT))  out.println("LEFT");
            if (keysPressed.contains(KeyEvent.VK_RIGHT)) out.println("RIGHT");
            if (keysPressed.contains(KeyEvent.VK_ESCAPE)) out.println("ESC");
        });
        inputTimer.start();

        // Timer de render: lê o último estado e redesenha (~33 fps)
        // Corre na EDT — repaint() é substituído por processamento direto aqui.
        renderTimer = new Timer(30, e -> {
            String state = lastWorldState;
            if (state != null) {
                parseWorld(state);   // parse rápido, sem I/O
                repaint();           // agendado na EDT onde já estamos
            }
        });
        renderTimer.start();
    }

    // Chamado pelo socket reader thread — apenas guarda o estado, não bloqueia
    public void updateWorld(String msg) {
        // last-write-wins: se chegaram 2 estados antes do próximo render,
        // descartamos o anterior — é aceitável num jogo a 33fps.
        lastWorldState = msg;
        // NÃO chamamos repaint() aqui — o renderTimer trata disso
    }

    // Parse isolado — pode ser chamado sem preocupações de thread safety
    // porque lastWorldState é volatile e parseWorld não faz I/O nem Swing calls
    private void parseWorld(String msg) {
        try {
            gameObjectsMap.clear();
            String[] parts = msg.split(",");
            for (int i = 1; i < parts.length; i++) {
                String[] data = parts[i].split(":");
                String category = data[0].trim();
                if (category.equals("P")) {
                    String user = data[1].trim();
                    int score = data.length > 6 ? Integer.parseInt(data[6].trim()) : 0;
                    gameObjectsMap.put(user, new GameObject(user,
                        Double.parseDouble(data[2]), Double.parseDouble(data[3]),
                        Double.parseDouble(data[4]), Double.parseDouble(data[5]), score));
                } else if (category.equals("O")) {
                    String id = "OBJ_" + data[1].trim();
                    gameObjectsMap.put(id, new GameObject(id,
                        Double.parseDouble(data[2]), Double.parseDouble(data[3]),
                        Integer.parseInt(data[4]),   Double.parseDouble(data[5])));
                }
            }
        } catch (Exception e) {
            System.out.println("Erro no parser: " + e.getMessage());
        }
    }

    public void stopInput() {
        if (inputTimer  != null) inputTimer.stop();
        if (renderTimer != null) renderTimer.stop();  // ← para ambos os timers
    }

    @Override
    protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Fundo "fora" do mapa (para quando a janela for esticada)
            g2.setColor(Color.LIGHT_GRAY);
            g2.fillRect(0, 0, getWidth(), getHeight());

            // Fundo da área jogável fixa (1920 x 1080)
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, 1920, 1080);

            // Limites do espaço jogável
            g2.setColor(Color.DARK_GRAY);
            g2.setStroke(new BasicStroke(5)); 
            g2.drawRect(0, 0, 1905, 985);

            gameObjectsMap.values().forEach(obj -> {
                int ix = (int) obj.x;
                int iy = (int) obj.y;

                if (obj.type.equals("P")) {
                    // --- DESENHAR JOGADOR ---
                    int radius = (int) Math.sqrt(obj.massa * 20); 
                    g2.setStroke(new BasicStroke(3)); 
                
                    // Cor da Borda (Azul para próprio, Vermelho para outros)
                    if (myUsername != null && obj.id.equals(myUsername)) g2.setColor(Color.BLUE);
                    else g2.setColor(Color.RED);
                    g2.drawOval(ix - radius, iy - radius, radius * 2, radius * 2);
                
                    // Interior Preto
                    g2.setColor(Color.BLACK);
                    g2.fillOval(ix - radius, iy - radius, radius * 2, radius * 2);
                
                    // Linha de Direção Branca
                    g2.setColor(Color.WHITE);
                    int targetX = (int) (ix + Math.cos(obj.angulo) * radius);
                    int targetY = (int) (iy + Math.sin(obj.angulo) * radius);
                    g2.drawLine(ix, iy, targetX, targetY);

                    FontMetrics fm = g2.getFontMetrics();
                    String labelNome  = obj.id;
                    String labelScore = "⚡ " + obj.score;  // capturas PvP

                    int nomeW  = fm.stringWidth(labelNome);
                    int scoreW = fm.stringWidth(labelScore);

                    // Fundo semitransparente para legibilidade sobre qualquer cor de fundo
                    int pad = 3;
                    int labelX = ix - Math.max(nomeW, scoreW) / 2 - pad;
                    int labelY = iy - radius - fm.getHeight() * 2 - pad * 2 - 4;
                    int labelW = Math.max(nomeW, scoreW) + pad * 2;
                    int labelH = fm.getHeight() * 2 + pad * 2 + 2;

                    g2.setColor(new Color(0, 0, 0, 140));  // preto com 55% opacidade
                    g2.fillRoundRect(labelX, labelY, labelW, labelH, 6, 6);

                    // Nome (branco)
                    g2.setColor(Color.WHITE);
                    g2.drawString(labelNome, ix - nomeW / 2, iy - radius - fm.getHeight() - pad - 2);

                    // Score — amarelo dourado para destaque
                    g2.setColor(new Color(255, 215, 0));
                    g2.drawString(labelScore, ix - scoreW / 2, iy - radius - pad);
                } 
                else if (obj.type.equals("O")) {
                    // --- DESENHAR OBJETO ---
                    int radius = (int) obj.massa; 
                    
                    // Verde para comestível (1), Vermelho para venenoso (2)
                    if (obj.objectType == 1) g2.setColor(Color.GREEN);
                    else g2.setColor(Color.RED);
                    
                    g2.fillOval(ix - radius, iy - radius, radius * 2, radius * 2);
                }
            });
        }
}

    class RankingPanel extends JPanel {
        JTextArea area = new JTextArea(15, 20);
        JButton backBtn = new JButton("Voltar ao Menu");

        public RankingPanel() {
            setLayout(new BorderLayout());
            area.setEditable(false);
            area.setFont(new Font("Monospaced", Font.BOLD, 14));
            
            add(new JLabel("--- TOP PONTUAÇÕES ---", SwingConstants.CENTER), BorderLayout.NORTH);
            add(new JScrollPane(area), BorderLayout.CENTER);
            add(backBtn, BorderLayout.SOUTH);

            backBtn.addActionListener(e -> mudarParaMenu());
        }

        public void setTexto(String txt) { area.setText(txt); }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GameClient());
    }
}