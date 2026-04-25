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

    private Map<String, PlayerData> playerPositions = new ConcurrentHashMap<>();

    private String myUsername; 

    public GameClient() {
        initGUI();
        conectarServidor();
    }

    private void initGUI() {
        setTitle("Mini-Jogo Concorrente");
        setSize(800, 600);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        
        loginPanel = new LoginPanel();
        add(loginPanel);
        
        setVisible(true);
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
    class PlayerData {
        double x, y, angulo, massa;

        PlayerData(double x, double y, double angulo, double massa) {
            this.x = x;
            this.y = y;
            this.angulo = angulo;
            this.massa = massa;
        }
    }

    class GamePanel extends JPanel {
        private final Set<Integer> keysPressed = new HashSet<>();
        private Timer inputTimer;

        public GamePanel() {
            setFocusable(true);
            requestFocusInWindow();

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    keysPressed.add(e.getKeyCode());
                }
                @Override
                public void keyReleased(KeyEvent e) {
                    keysPressed.remove(e.getKeyCode());
                }
            });

            // Envia input ao servidor ~33x por segundo
            inputTimer = new Timer(30, e -> {
                if (keysPressed.contains(KeyEvent.VK_UP))    out.println("UP");
                if (keysPressed.contains(KeyEvent.VK_DOWN))  out.println("DOWN");
                if (keysPressed.contains(KeyEvent.VK_LEFT))  out.println("LEFT");
                if (keysPressed.contains(KeyEvent.VK_RIGHT)) out.println("RIGHT");
            });
            inputTimer.start();
        }

        // Chama isto quando o jogo terminar para não ficar a enviar mensagens
        public void stopInput() {
            if (inputTimer != null) inputTimer.stop();
        }

        public void updateWorld(String msg) {
            try {
                String[] parts = msg.split(",");
                // O parts[0] é "DATA"
                for (int i = 1; i < parts.length; i++) {
                    String[] data = parts[i].split(":");
                    String user = data[0].trim(); //// Uso do trim para evitar problemas de nomes que estejam com espaços no fim
                    double x = Double.parseDouble(data[1]);
                    double y = Double.parseDouble(data[2]);
                    double ang = Double.parseDouble(data[3]);
                    double m = Double.parseDouble(data[4]);

                    // Guardamos o objeto completo no mapa
                    playerPositions.put(user, new PlayerData(x, y, ang, m));
                }
                repaint();
            } catch (Exception e) {
                System.out.println("Erro no parser: " + e.getMessage());
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
            // 1. Desenhar o mapa (temos um problema: acredito que precisamos
            // garantir que, quando um jogador fica com o ecrã do jogo igual ao ecrã
            // do portátil, o jogador consiga explorar todo o espaço. Da maneira que está definida no
            // server, o espaço para o jogador explorar é bem menor do que o espaço do ecrã dos nossos
            // portáteis.)
            
            // Fundo do mapa (vazio)
            g2.setColor(Color.WHITE); // É dito para usarmos a cor branca no enunciado
            g2.fillRect(0, 0, getWidth(), getHeight());

            // Limites do espaço (borda nos 4 lados)
            g2.setColor(Color.DARK_GRAY);
            g2.setStroke(new BasicStroke(5)); // Espessura da parede
            g2.drawRect(0, 0, getWidth(), getHeight());

            playerPositions.forEach((user, p) -> {
                int radius = (int) Math.sqrt(p.massa * 20); 
                int ix = (int) p.x;
                int iy = (int) p.y;
            
                // --- AQUI ---
                // Definimos a espessura da linha para 3 píxeis
                g2.setStroke(new BasicStroke(3)); 
            
                // 1. Cor da Borda
                // Uso do trim para evitar problemas de nomes que estejam com espaços no fim
                if (myUsername != null && user.trim().equals(myUsername.trim()))
                    g2.setColor(Color.BLUE);
                else 
                    g2.setColor(Color.RED);

                // Desenha a borda (agora com 3px de espessura)
                g2.drawOval(ix - radius, iy - radius, radius * 2, radius * 2);
            
                // 2. Interior Preto (Avatar)
                g2.setColor(Color.BLACK);
                g2.fillOval(ix - radius, iy - radius, radius * 2, radius * 2);
            
                // 3. Linha de Direção (Branca) - Também será desenhada com 3px!
                g2.setColor(Color.WHITE);
                int targetX = (int) (ix + Math.cos(p.angulo) * radius);
                int targetY = (int) (iy + Math.sin(p.angulo) * radius);
                g2.drawLine(ix, iy, targetX, targetY);
            
                // Nome por cima
                g2.drawString(user, ix - radius, iy - radius - 5);
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